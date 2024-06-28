package biz.cunning.cunning_document_scanner.fallback;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import androidx.appcompat.app.AppCompatActivity;
import biz.cunning.cunning_document_scanner.R;
import biz.cunning.cunning_document_scanner.fallback.constants.DefaultSetting;
import biz.cunning.cunning_document_scanner.fallback.constants.DocumentScannerExtra;
import biz.cunning.cunning_document_scanner.fallback.extensions.move;
import biz.cunning.cunning_document_scanner.fallback.extensions.onClick;
import biz.cunning.cunning_document_scanner.fallback.extensions.saveToFile;
import biz.cunning.cunning_document_scanner.fallback.extensions.screenHeight;
import biz.cunning.cunning_document_scanner.fallback.extensions.screenWidth;
import biz.cunning.cunning_document_scanner.fallback.models.Document;
import biz.cunning.cunning_document_scanner.fallback.models.Point;
import biz.cunning.cunning_document_scanner.fallback.models.Quad;
import biz.cunning.cunning_document_scanner.fallback.ui.ImageCropView;
import biz.cunning.cunning_document_scanner.fallback.utils.CameraUtil;
import biz.cunning.cunning_document_scanner.fallback.utils.FileUtil;
import biz.cunning.cunning_document_scanner.fallback.utils.ImageUtil;
import java.io.File;

public class DocumentScannerActivity extends AppCompatActivity {
    private Camera camera;
    private boolean isFlashlightOn = false;

    private void turnOnFlashLight() {
        if (camera != null) {
            Camera.Parameters params = camera.getParameters();
            params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            camera.setParameters(params);
            camera.startPreview();
            isFlashlightOn = true;
        }
    }

    private void turnOffFlashLight() {
        if (camera != null) {
            Camera.Parameters params = camera.getParameters();
            params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(params);
            camera.stopPreview();
            isFlashlightOn = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (camera != null) {
            if (isFlashlightOn) {
                turnOffFlashLight();
            }
            camera.release();
            camera = null;
        }
    }

    // Existing properties
    private var maxNumDocuments = DefaultSetting.MAX_NUM_DOCUMENTS;
    private var croppedImageQuality = DefaultSetting.CROPPED_IMAGE_QUALITY;
    private val cropperOffsetWhenCornersNotFound = 100.0;
    private var document: Document? = null;
    private val documents = mutableListOf<Document>();
    private val cameraUtil = CameraUtil(
        this,
        onPhotoCaptureSuccess = {
                originalPhotoPath ->

            if (documents.size == maxNumDocuments - 1) {
                val newPhotoButton: ImageButton = findViewById(R.id.new_photo_button);
                newPhotoButton.isClickable = false;
                newPhotoButton.visibility = View.INVISIBLE;
            }

            val photo: Bitmap? = try {
                ImageUtil().getImageFromFilePath(originalPhotoPath);
            } catch (exception: Exception) {
                finishIntentWithError("Unable to get bitmap: ${exception.localizedMessage}");
                return@CameraUtil;
            }

            if (photo == null) {
                finishIntentWithError("Document bitmap is null.");
                return@CameraUtil;
            }

            val corners = try {
                val (topLeft, topRight, bottomLeft, bottomRight) = getDocumentCorners(photo);
                Quad(topLeft, topRight, bottomRight, bottomLeft);
            } catch (exception: Exception) {
                finishIntentWithError(
                    "unable to get document corners: ${exception.message}"
                );
                return@CameraUtil;
            }

            document = Document(originalPhotoPath, photo.width, photo.height, corners);

            try {
                imageView.setImagePreviewBounds(photo, screenWidth, screenHeight);
                imageView.setImage(photo);
                val cornersInImagePreviewCoordinates = corners
                    .mapOriginalToPreviewImageCoordinates(
                        imageView.imagePreviewBounds,
                        imageView.imagePreviewBounds.height() / photo.height
                    );
                imageView.setCropper(cornersInImagePreviewCoordinates);
            } catch (exception: Exception) {
                finishIntentWithError(
                    "unable get image preview ready: ${exception.message}"
                );
                return@CameraUtil;
            }
        },
        onCancelPhoto = {
            if (documents.isEmpty()) {
                onClickCancel();
            }
        }
    );

    private lateinit var imageView: ImageCropView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_crop);
        imageView = findViewById(R.id.image_view);

        try {
            var userSpecifiedMaxImages: Int? = null;
            intent.extras?.get(DocumentScannerExtra.EXTRA_MAX_NUM_DOCUMENTS)?.let {
                if (it.toString().toIntOrNull() == null) {
                    throw Exception(
                        "${DocumentScannerExtra.EXTRA_MAX_NUM_DOCUMENTS} must be a positive number"
                    );
                }
                userSpecifiedMaxImages = it as Int;
                maxNumDocuments = userSpecifiedMaxImages as Int;
            }

            intent.extras?.get(DocumentScannerExtra.EXTRA_CROPPED_IMAGE_QUALITY)?.let {
                if (it !is Int || it < 0 || it > 100) {
                    throw Exception(
                        "${DocumentScannerExtra.EXTRA_CROPPED_IMAGE_QUALITY} must be a number " +
                                "between 0 and 100"
                    );
                }
                croppedImageQuality = it;
            }

            // Check if flashlight should be turned on
            val flashLightOn = intent.getBooleanExtra("FLASHLIGHT_ON", false);
            if (flashLightOn) {
                camera = Camera.open();
                turnOnFlashLight();
            }
        } catch (exception: Exception) {
            finishIntentWithError(
                "invalid extra: ${exception.message}"
            );
            return;
        }

        val newPhotoButton: ImageButton = findViewById(R.id.new_photo_button);
        val completeDocumentScanButton: ImageButton = findViewById(
            R.id.complete_document_scan_button
        );
        val retakePhotoButton: ImageButton = findViewById(R.id.retake_photo_button);

        newPhotoButton.onClick { onClickNew(); };
        completeDocumentScanButton.onClick { onClickDone(); };
        retakePhotoButton.onClick { onClickRetake(); };

        try {
            openCamera();
        } catch (exception: Exception) {
            finishIntentWithError(
                "error opening camera: ${exception.message}"
            );
        }
    }

    private fun getDocumentCorners(photo: Bitmap): List<Point> {
        val cornerPoints: List<Point>? = null;
        return cornerPoints ?: listOf(
            Point(0.0, 0.0).move(
                cropperOffsetWhenCornersNotFound,
                cropperOffsetWhenCornersNotFound
            ),
            Point(photo.width.toDouble(), 0.0).move(
                -cropperOffsetWhenCornersNotFound,
                cropperOffsetWhenCornersNotFound
            ),
            Point(0.0, photo.height.toDouble()).move(
                cropperOffsetWhenCornersNotFound,
                -cropperOffsetWhenCornersNotFound
            ),
            Point(photo.width.toDouble(), photo.height.toDouble()).move(
                -cropperOffsetWhenCornersNotFound,
                -cropperOffsetWhenCornersNotFound
            )
        );
    }

    private void openCamera() {
        document = null;
        cameraUtil.openCamera(documents.size);
    }

    private void addSelectedCornersAndOriginalPhotoPathToDocuments() {
        document?.let { document ->
            val cornersInOriginalImageCoordinates = imageView.corners
                .mapPreviewToOriginalImageCoordinates(
                    imageView.imagePreviewBounds,
                    imageView.imagePreviewBounds.height() / document.originalPhotoHeight
                );
            document.corners = cornersInOriginalImageCoordinates;
            documents.add(document);
        }
    }

    private void onClickNew() {
        addSelectedCornersAndOriginalPhotoPathToDocuments();
        openCamera();
    }

    private void onClickDone() {
        addSelectedCornersAndOriginalPhotoPathToDocuments();
        cropDocumentAndFinishIntent();
    }

    private void onClickRetake() {
        document?.let { document -> File(document.originalPhotoFilePath).delete(); };
        openCamera();
    }

    private void onClickCancel() {
        setResult(Activity.RESULT_CANCELED);
        finish();
    }

    private void cropDocumentAndFinishIntent() {
        val croppedImageResults = arrayListOf<String>();
        for ((pageNumber, document) in documents.withIndex()) {
            val croppedImage: Bitmap? = try {
                ImageUtil().crop(
                    document.originalPhotoFilePath,
                    document.corners
                );
            } catch (exception: Exception) {
                finishIntentWithError("unable to crop image: ${exception.message}");
                return;
            }

            if (croppedImage == null) {
                finishIntentWithError("Result of cropping is null");
                return;
            }

            File(document.originalPhotoFilePath).delete();

            try {
                val croppedImageFile = FileUtil().createImageFile(this, pageNumber);
                croppedImage.saveToFile(croppedImageFile, croppedImageQuality);
                croppedImageResults.add(Uri.fromFile(croppedImageFile).toString());
            } catch (exception: Exception) {
                finishIntentWithError(
                    "unable to save cropped image: ${exception.message}"
                );
            }
        }

        setResult(
            Activity.RESULT_OK,
            Intent().putExtra("croppedImageResults", croppedImageResults)
        );
        finish();
    }

    private void finishIntentWithError(errorMessage: String) {
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra("error", errorMessage)
        );
        finish();
    }
}
