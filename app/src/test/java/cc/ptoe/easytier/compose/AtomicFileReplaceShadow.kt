package cc.ptoe.easytier.compose

import android.util.AtomicFile
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Model Android's replacing rename on Windows, where Java File.renameTo cannot replace.
 * All application serialization/recovery logic and AtomicFile.startWrite/openRead stay real.
 */
@Implements(value = AtomicFile::class, minSdk = 30)
class AtomicFileReplaceShadow {
    @RealObject private lateinit var atomic: AtomicFile

    @Implementation
    protected fun finishWrite(stream: FileOutputStream?) {
        if (stream == null) return
        stream.fd.sync()
        stream.close()
        Files.move(File(atomic.baseFile.path + ".new").toPath(), atomic.baseFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
