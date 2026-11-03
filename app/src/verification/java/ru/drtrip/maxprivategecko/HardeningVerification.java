package ru.drtrip.maxprivategecko;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import androidx.exifinterface.media.ExifInterface;
import android.util.Log;
import java.io.*;

/** Only synthetic fixtures in the verification package. Never packaged in release. */
public final class HardeningVerification {
    private static volatile boolean result;
    public static boolean passed() { return result; }
    public static void run(Context context) {
        new Thread(() -> {
            TransferScope scope = new TransferScope();
            File folder = new File(context.getCacheDir(), "max_selected_files/native-verification");
            scope.own(folder);
            boolean passed = false;
            try {
                if (!folder.mkdirs()) throw new IOException("fixture directory");
                File original = new File(folder,"original.jpg");
                Bitmap bitmap = Bitmap.createBitmap(16,8,Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.BLUE);
                try(OutputStream out=new FileOutputStream(original)){bitmap.compress(Bitmap.CompressFormat.JPEG,95,out);}
                bitmap.recycle();
                ExifInterface exif = new ExifInterface(original.getAbsolutePath());
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE,"1/1,2/1,3/1");
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,"N");
                exif.setAttribute(ExifInterface.TAG_ORIENTATION,"6");
                exif.saveAttributes();
                File clean = ImagePrivacy.clean(original,0,scope);
                ExifInterface cleaned = new ExifInterface(clean.getAbsolutePath());
                if(cleaned.getAttribute(ExifInterface.TAG_GPS_LATITUDE)!=null)throw new IOException("GPS retained");
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
                BitmapFactory.decodeFile(clean.getAbsolutePath(),bounds);
                if(bounds.outWidth!=8||bounds.outHeight!=16||original.exists())throw new IOException("orientation/copy");
                scope.cancel();
                scope.purgeOwned(context.getCacheDir());
                if(folder.exists())throw new IOException("cache retained");
                passed=true;
            } catch(Exception error) {
                Log.i("MaxPrivacyCheck","native-fixture-error="+error.getClass().getSimpleName());
            } finally {
                scope.cancel();
                try{scope.purgeOwned(context.getCacheDir());}catch(IOException ignored){}
            }
            Log.i("MaxPrivacyCheck","image-metadata-and-cache="+passed);
            result=passed;
        },"SyntheticPrivacyVerification").start();
    }
}
