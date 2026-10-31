// Test-only stand-ins for image cache APIs. Never packaged in the Android app.
package android.graphics
class Bitmap(val byteCount: Int = 0)
object BitmapFactory {
    class Options { var inJustDecodeBounds = false; var outWidth = 0; var outHeight = 0; var inSampleSize = 1 }
    fun decodeByteArray(b: ByteArray, o: Int, len: Int, opts: Options): Bitmap? = null
}
