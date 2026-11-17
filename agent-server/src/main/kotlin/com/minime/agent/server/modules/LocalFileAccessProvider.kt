package com.minime.agent.server.modules

import com.minime.code.feature.workspace.domain.FileAccessProvider
import com.minime.code.feature.workspace.domain.FileEntry
import com.minime.code.feature.workspace.domain.sha256Fingerprint
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/**
 * 服务端本地文件访问实现：把 AI 视角的「容器路径」直接映射到 agent-server 容器内的真实文件系统。
 *
 * 与 App 端的 LocalFileAccess 语义对齐：
 * - `~/xxx` 展开为 `$HOME/xxx`（容器内 HOME=/root）；
 * - `~/workspace` 与 `/workspace` 等价（部署时已建软链 /root/workspace → /workspace，这里归一）；
 * - 其它 `/` 开头路径视为绝对路径。
 *
 * 一期只需支持 skill 扫描用到的 readFile / listFiles / listFilesRecursive / exists / isDirectory，
 * 其余方法给出纯本地的合理实现（服务端未来接更多文件工具时可直接复用）。无远程 SFTP 语义。
 */
class LocalFileAccessProvider(
    /** home 真实路径：生产取 HOME（容器内 /root）；单测可注入临时目录。 */
    private val homePath: String = System.getenv("HOME") ?: "/root",
    /** workspace 真实路径：生产 /workspace；单测可注入临时目录。 */
    private val workspacePath: String = "/workspace"
) : FileAccessProvider {

    private val home: File = File(homePath)

    /** 把容器路径解析为容器内真实的 [File]。 */
    private fun resolve(path: String): File {
        val p = path.trim()
        val expanded = when {
            // ~/workspace 语义等价于 workspace 根（部署时软链），统一到 workspacePath
            p == "~/workspace" || p.startsWith("~/workspace/") ->
                workspacePath + p.removePrefix("~/workspace")
            p == "~" || p.startsWith("~/") ->
                home.resolve(p.removePrefix("~").trimStart('/')).path
            // 绝对路径 /workspace/... 也映射到 workspacePath
            p == "/workspace" || p.startsWith("/workspace/") ->
                workspacePath + p.removePrefix("/workspace")
            else -> p
        }
        return File(expanded)
    }

    override fun readFile(path: String): String = resolve(path).readText(Charsets.UTF_8)

    override fun readLines(path: String): Sequence<String> =
        resolve(path).readLines(Charsets.UTF_8).asSequence()

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) {
        val f = resolve(path)
        if (!overwrite && f.exists()) throw FileAlreadyExistsException(f)
        f.parentFile?.mkdirs()
        f.writeText(content, encoding)
    }

    override fun exists(path: String): Boolean = resolve(path).exists()

    override fun isDirectory(path: String): Boolean = resolve(path).isDirectory

    override fun isFile(path: String): Boolean = resolve(path).isFile

    override fun fileSize(path: String): Long = resolve(path).length()

    override fun lastModified(path: String): Long = resolve(path).lastModified()

    override fun fingerprint(path: String): String? {
        val f = resolve(path)
        if (!f.isFile) return null
        return sha256Fingerprint(f.readBytes())
    }

    override fun permissions(path: String): String {
        val f = resolve(path)
        return buildString(3) {
            append(if (f.canRead()) 'r' else '-')
            append(if (f.canWrite()) 'w' else '-')
            append(if (f.canExecute()) 'x' else '-')
        }
    }

    override fun listFiles(path: String): List<FileEntry> {
        val dir = resolve(path)
        if (!dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: emptyArray()).map { f ->
            FileEntry(
                name = f.name,
                isDirectory = f.isDirectory,
                size = if (f.isFile) f.length() else 0L,
                lastModified = f.lastModified(),
                localFile = f,
                permissions = buildString(3) {
                    append(if (f.canRead()) 'r' else '-')
                    append(if (f.canWrite()) 'w' else '-')
                    append(if (f.canExecute()) 'x' else '-')
                }
            )
        }
    }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
        val root = resolve(path)
        if (!root.isDirectory) return emptyList()
        val out = mutableListOf<String>()
        fun walk(dir: File, depth: Int, rel: String) {
            if (depth > maxDepth) return
            (dir.listFiles() ?: emptyArray()).forEach { f ->
                val childRel = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                if (f.isDirectory) walk(f, depth + 1, childRel) else out += childRel
            }
        }
        walk(root, 1, "")
        return out
    }

    override fun readBytes(path: String): ByteArray = resolve(path).readBytes()

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        val f = resolve(path)
        if (!overwrite && f.exists()) throw FileAlreadyExistsException(f)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
    }

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long {
        val f = resolve(path)
        if (!overwrite && f.exists()) throw FileAlreadyExistsException(f)
        f.parentFile?.mkdirs()
        var count = 0L
        input.use { src ->
            Files.newOutputStream(f.toPath()).use { dst ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    dst.write(buf, 0, n)
                    count += n
                }
            }
        }
        return count
    }

    override fun copyToLocal(path: String): File = resolve(path)

    override fun delete(path: String) {
        val f = resolve(path)
        if (!f.delete() && f.exists()) throw IOException("删除失败: $path")
    }

    override fun deleteRecursively(path: String) {
        val f = resolve(path)
        if (!f.exists()) return
        if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    override fun rename(path: String, newPath: String) {
        val src = resolve(path)
        val dst = resolve(newPath)
        if (!src.exists()) throw NoSuchFileException(path)
        if (dst.exists()) throw FileAlreadyExistsException(dst.absolutePath)
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) throw IOException("重命名失败: $path -> $newPath")
    }

    override fun copy(path: String, newPath: String, overwrite: Boolean) {
        val src = resolve(path)
        val dst = resolve(newPath)
        if (!src.exists()) throw NoSuchFileException(path)
        if (dst.exists() && !overwrite) throw FileAlreadyExistsException(dst.absolutePath)
        dst.parentFile?.mkdirs()
        if (src.isDirectory) src.copyRecursively(dst, overwrite = overwrite)
        else src.copyTo(dst, overwrite = overwrite)
    }

    override fun move(path: String, newPath: String, overwrite: Boolean) {
        copy(path, newPath, overwrite)
        deleteRecursively(path)
    }

    override fun mkdirs(path: String) {
        resolve(path).mkdirs()
    }

    override fun parentPath(path: String): String? = resolve(path).parentFile?.path

    override fun toDisplayPath(path: String): String = path
}
