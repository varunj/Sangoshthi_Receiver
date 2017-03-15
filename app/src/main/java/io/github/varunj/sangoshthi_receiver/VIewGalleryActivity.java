package io.github.varunj.sangoshthi_receiver;

        import android.content.Intent;
        import android.database.Cursor;
        import android.graphics.Bitmap;
        import android.media.MediaScannerConnection;
        import android.net.Uri;
        import android.os.Bundle;
        import android.os.Environment;
        import android.provider.MediaStore;
        import android.support.v7.app.AppCompatActivity;
        import android.util.Log;
        import android.view.View;
        import android.widget.Button;

        import java.io.File;
        import java.util.ArrayList;
        import java.util.List;

        import static android.R.attr.data;

/**
 * Created by Varun on 14-Mar-17.
 */

public class VIewGalleryActivity extends AppCompatActivity {



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        openGallery();
    }

    private void openGallery() {
        File folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS + "/Sangoshthi");

        String bucketId = "";
        final String[] projection = new String[] {"DISTINCT " + MediaStore.Images.Media.BUCKET_DISPLAY_NAME + ", " + MediaStore.Images.Media.BUCKET_ID};
        final Cursor cur = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, null);
        while (cur != null && cur.moveToNext()) {
            final String bucketName = cur.getString((cur.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)));
            if (bucketName.equals("Sangoshthi")) {
                bucketId = cur.getString((cur.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_ID)));
                break;
            }
        }

        Uri mediaUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
//        Uri mediaUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        if (bucketId.length() > 0) {
            mediaUri = mediaUri.buildUpon()
                    .authority("media")
                    .appendQueryParameter("bucketId", bucketId)
                    .build();
        }

        Intent intent = new Intent(Intent.ACTION_VIEW, mediaUri);
        startActivity(intent);


    }

}
