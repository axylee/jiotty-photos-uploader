package net.yudichev.googlephotosupload.app;

import com.google.inject.BindingAnnotation;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.varstore.VarStore;
import net.yudichev.jiotty.connector.google.photos.GooglePhotosAlbum;
import net.yudichev.jiotty.connector.google.photos.GooglePhotosClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.stream.Collectors.toConcurrentMap;
import static net.yudichev.googlephotosupload.app.Bindings.Backpressured;
import static net.yudichev.googlephotosupload.app.Bindings.RootDir;
import static net.yudichev.jiotty.common.lang.CompletableFutures.completedFuture;
import static net.yudichev.jiotty.common.lang.CompletableFutures.logErrorOnFailure;

final class GooglePhotosUploaderImpl extends BaseLifecycleComponent implements GooglePhotosUploader {
    private static final Logger logger = LoggerFactory.getLogger(GooglePhotosUploaderImpl.class);
    private static final String VAR_STORE_KEY = "photosUploader";

    private final VarStore varStore;
    private final GooglePhotosClient googlePhotosClient;

    private final ExecutorService executorService;
    private final RemoteApiResultHandler backOffHandler;
    private final RemoteApiResultHandler invalidMediaItemHandler;
    private final AlbumManager albumManager;
    private final StateSaver stateSaver;
    private Map<Path, CompletableFuture<ItemState>> uploadedItemStateByPath;
    private UploadState uploadState;

    @Inject
    GooglePhotosUploaderImpl(VarStore varStore,
                             GooglePhotosClient googlePhotosClient,
                             @RootDir Path rootDir,
                             @Backpressured ExecutorService executorService,
                             @Backoff RemoteApiResultHandler backOffHandler,
                             @InvalidMediaItem RemoteApiResultHandler invalidMediaItemHandler,
                             StateSaverFactory stateSaverFactory,
                             AlbumManager albumManager) {
        this.varStore = checkNotNull(varStore);
        this.executorService = checkNotNull(executorService);
        this.backOffHandler = checkNotNull(backOffHandler);
        this.invalidMediaItemHandler = checkNotNull(invalidMediaItemHandler);
        this.albumManager = checkNotNull(albumManager);
        checkArgument(Files.isDirectory(rootDir), "Path is not a directory: %s", rootDir);
        stateSaver = stateSaverFactory.create("uploaded-items", this::saveState);
        this.googlePhotosClient = checkNotNull(googlePhotosClient);
    }

    @Override
    public CompletableFuture<Void> uploadFile(Path file) {
        Path parentDir = file.getParent();
        return albumManager.albumForDir(parentDir)
                .map(albumFuture -> albumFuture
                        .thenApply(Optional::of))
                .orElse(CompletableFuture.completedFuture(Optional.empty()))
                .thenCompose(album -> uploadedItemStateByPath.compute(file,
                        (theFile, currentFuture) -> {
                            if (currentFuture == null || currentFuture.isCompletedExceptionally()) {
                                logger.info("Scheduling upload of {}", file);
                                currentFuture = doUpload(album, theFile);
                            } else {
                                if (currentFuture.isDone()) {
                                    if (!currentFuture.getNow(null).mediaId().isPresent()) {
                                        logger.info("Permanently failed before, skipping: {}", file);
                                    } else if (!currentFuture.getNow(null).albumId().isPresent()) {
                                        // TODO completely remove this condition, only possible with my broken data
                                        //  otherwise it'll fail on on items in root dir and will keep uploading them
                                        logger.info("Previously uploaded, but not in album, uploading again: {}", file);
                                        currentFuture = doUpload(album, theFile);
                                    } else {
                                        logger.info("Already uploaded, skipping: {}", file);
                                    }
                                } else {
                                    logger.error("Unexpected future state for {}: {}", file, currentFuture);
                                }
                            }
                            return currentFuture;
                        }))
                .thenApply(theItem -> {
                    stateSaver.save();
                    return theItem;
                })
                .thenApply(aVoid -> {
                    backOffHandler.reset();
                    return false;
                })
                .exceptionally(exception -> {
                    String operationName = "uploading file " + file;
                    boolean shouldRetry = backOffHandler.handle(operationName, exception);
                    boolean invalidMediaItem = invalidMediaItemHandler.handle(operationName, exception);
                    if (invalidMediaItem) {
                        uploadedItemStateByPath.computeIfPresent(file, (path, existingStateFuture) ->
                                CompletableFuture.completedFuture(ItemState.builder().build()));
                    }
                    if (!shouldRetry && !invalidMediaItem) {
                        logger.error("failed operation '{}': {}", operationName, exception.getMessage());
                    }
                    return shouldRetry;
                })
                .thenCompose(shouldRetry -> {
                    if (shouldRetry) {
                        logger.debug("Retrying upload of {}", file);
                        return uploadFile(file);
                    }
                    return completedFuture();
                })
                .whenComplete(logErrorOnFailure(logger, "Unhandled exception uploading file %s", file));
    }

    @Override
    protected void doStart() {
        uploadState = varStore.readValue(UploadState.class, VAR_STORE_KEY).orElseGet(() -> UploadState.builder().build());
        uploadedItemStateByPath = uploadState.uploadedMediaItemIdByAbsolutePath().entrySet().stream()
                .collect(toConcurrentMap(
                        entry -> Paths.get(entry.getKey()),
                        entry -> CompletableFuture.completedFuture(entry.getValue())));
    }

    @Override
    protected void doStop() {
        stateSaver.close();
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private CompletableFuture<ItemState> doUpload(Optional<GooglePhotosAlbum> album, Path file) {
        Optional<String> albumId = album.map(GooglePhotosAlbum::getId);
        return googlePhotosClient.uploadMediaItem(albumId, file, executorService)
                .thenApply(googleMediaItem -> {
                    logger.info("Uploaded file {} as media item {} and album {}", file, googleMediaItem.getId(), album.map(GooglePhotosAlbum::getTitle));
                    return ItemState.builder()
                            .setMediaId(googleMediaItem.getId())
                            .setAlbumId(albumId)
                            .build();
                });
    }

    private void saveState() {
        UploadState newUploadState = UploadState.of(
                uploadedItemStateByPath.entrySet().stream()
                        .filter(entry -> entry.getValue().isDone() && !entry.getValue().isCompletedExceptionally())
                        .collect(toImmutableMap(
                                entry -> entry.getKey().toString(),
                                entry -> entry.getValue().getNow(null))));
        if (!newUploadState.equals(uploadState)) {
            uploadState = newUploadState;
            varStore.saveValue(VAR_STORE_KEY, uploadState);
        }
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Backoff {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface InvalidMediaItem {
    }
}
