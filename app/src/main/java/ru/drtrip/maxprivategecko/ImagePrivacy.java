package ru.drtrip.maxprivategecko;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import androidx.exifinterface.media.ExifInterface;
import java.io.*;

/** Re-encodes a new private copy; never edits the document provider's original. */
final class ImagePrivacy {
    static File clean(File original, int index, TransferScope scope) throws IOException {
        scope.check();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(original.getAbsolutePath(), bounds);
        String mime = bounds.outMimeType;
        if (!("image/jpeg".equals(mime) || "image/png".equals(mime) || "image/webp".equals(mime))) return original;
        if ("image/webp".equals(mime)) {
            byte[] header = new byte[24];
            try (InputStream in = scope.input(new FileInputStream(original))) {
                int n = in.read(header);
                if (n >= 21 && header[12]=='V' && header[13]=='P' && header[14]=='8'
                        && header[15]=='X' && (header[20] & 2) != 0) return original; // Keep animations intact.
            }
        }
        if (bounds.outWidth < 1 || bounds.outHeight < 1) throw new IOException("Invalid image");
        BitmapFactory.Options options = new BitmapFactory.Options();
        // A decompression bomb must not exhaust the parent process.
        options.inSampleSize = ImageLimits.sample(bounds.outWidth,bounds.outHeight,16000000L);
        Bitmap decoded = BitmapFactory.decodeFile(original.getAbsolutePath(), options);
        if (decoded == null) throw new IOException("Cannot decode selected image");
        Bitmap oriented = decoded;
        File output = null;
        try {
            scope.check();
            int orientation = new ExifInterface(original.getAbsolutePath())
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case 2: matrix.setScale(-1,1); break;
                case 3: matrix.setRotate(180); break;
                case 4: matrix.setScale(1,-1); break;
                case 5: matrix.setRotate(90); matrix.postScale(-1,1); break;
                case 6: matrix.setRotate(90); break;
                case 7: matrix.setRotate(270); matrix.postScale(-1,1); break;
                case 8: matrix.setRotate(270); break;
                default: break;
            }
            if (!matrix.isIdentity()) oriented = Bitmap.createBitmap(decoded,0,0,decoded.getWidth(),decoded.getHeight(),matrix,true);
            boolean png = oriented.hasAlpha() || "image/png".equals(mime);
            output = new File(original.getParentFile(), "private-image-" + (index+1) + (png ? ".png" : ".jpg"));
            // Never collide with a provider-supplied filename.
            if (output.equals(original)) output = new File(original.getParentFile(), "clean-" + output.getName());
            scope.own(output);
            try (OutputStream out = scope.output(new FileOutputStream(output))) {
                if (!oriented.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG,95,out))
                    throw new IOException("Cannot encode selected image");
            }
            scope.check();
            if (output.length() > 100L*1024*1024) throw new IOException("Clean image too large");
            if (!original.delete()) throw new IOException("Cannot remove private original copy");
            return output;
        } catch (IOException | RuntimeException error) {
            if (output != null) output.delete();
            throw error;
        } finally {
            if (oriented != decoded) oriented.recycle();
            decoded.recycle();
        }
    }
}
