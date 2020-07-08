package net.yudichev.googlephotosupload.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.nio.file.Path;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.CompletableFutures.toFutureOfList;

final class UploaderImpl implements Uploader {
    private static final Logger logger = LoggerFactory.getLogger(UploaderImpl.class);
    private final GooglePhotosUploader googlePhotosUploader;
    private final DirectoryStructureSupplier directoryStructureSupplier;
    private final AlbumManager albumManager;
    private final CloudAlbumsProvider cloudAlbumsProvider;
    private final ProgressStatusFactory progressStatusFactory;
    private final UploadStateManager uploadStateManager;
    private final ResourceBundle resourceBundle;

    @Inject
    UploaderImpl(GooglePhotosUploader googlePhotosUploader,
                 DirectoryStructureSupplier directoryStructureSupplier,
                 AlbumManager albumManager,
                 CloudAlbumsProvider cloudAlbumsProvider,
                 ProgressStatusFactory progressStatusFactory,
                 UploadStateManager uploadStateManager,
                 ResourceBundle resourceBundle) {
        this.googlePhotosUploader = checkNotNull(googlePhotosUploader);
        this.directoryStructureSupplier = checkNotNull(directoryStructureSupplier);
        this.albumManager = checkNotNull(albumManager);
        this.cloudAlbumsProvider = checkNotNull(cloudAlbumsProvider);
        this.progressStatusFactory = checkNotNull(progressStatusFactory);
        this.uploadStateManager = checkNotNull(uploadStateManager);
        this.resourceBundle = checkNotNull(resourceBundle);
    }

    @Override
    public CompletableFuture<Void> upload(Path rootDir, boolean resume) {
        if (!resume) {
            googlePhotosUploader.doNotResume();
        }
        var albumDirectoriesFuture = directoryStructureSupplier.listAlbumDirectories(rootDir);
        var cloudAlbumsByTitleFuture = cloudAlbumsProvider.listCloudAlbums();
        return albumDirectoriesFuture
                .thenCompose(albumDirectories -> cloudAlbumsByTitleFuture
                        .thenCompose(cloudAlbumsByTitle -> albumManager.listAlbumsByTitle(albumDirectories, cloudAlbumsByTitle)
                                .thenCompose(albumsByTitle -> {
                                    var directoryProgressStatus =
                                            progressStatusFactory.create(
                                                    resourceBundle.getString("uploaderDirectoryProgressTitle"),
                                                    Optional.of(albumDirectories.size()));
                                    var fileProgressStatus = progressStatusFactory.create(
                                            resourceBundle.getString("uploaderFileProgressTitle"),
                                            Optional.empty());
                                    try {
                                        return albumDirectories.stream()
                                                .map(albumDirectory -> googlePhotosUploader.uploadDirectory(
                                                        albumDirectory.path(),
                                                        albumDirectory.albumTitle().map(albumsByTitle::get),
                                                        fileProgressStatus
                                                )
                                                        .thenRun(directoryProgressStatus::incrementSuccess))
                                                .collect(toFutureOfList())
                                                .whenComplete((ignored, e) -> {
                                                    directoryProgressStatus.close(e == null);
                                                    fileProgressStatus.close(e == null);
                                                })
                                                .thenAccept(list -> logger.info("All done without errors, files uploaded: {}", list.size()));
                                    } catch (RuntimeException e) {
                                        directoryProgressStatus.closeUnsuccessfully();
                                        fileProgressStatus.closeUnsuccessfully();
                                        throw e;
                                    }
                                })));
    }

    @Override
    public int numberOfUploadedItems() {
        return uploadStateManager.get().uploadedMediaItemIdByAbsolutePath().size();
    }

    @Override
    public void forgetUploadState() {
        googlePhotosUploader.forgetUploadStateOnShutdown();
    }
}
