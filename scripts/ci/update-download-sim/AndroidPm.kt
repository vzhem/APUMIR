package android.content.pm
open class PackageManager { open fun getPackageArchiveInfo(path: String, flags: Int): PackageInfo? = null }
class PackageInfo(var packageName: String = "", var versionName: String? = null)
