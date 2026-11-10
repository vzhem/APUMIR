package android.content.pm
open class PackageManager {
    open fun getPackageArchiveInfo(path: String, flags: Int): PackageInfo? = null
    open fun getPackageInfo(name: String, flags: Int): PackageInfo = PackageInfo(name, "v11.74.500")
}
class PackageInfo(var packageName: String = "", var versionName: String? = null)
class ApplicationInfo(var sourceDir: String? = null)
