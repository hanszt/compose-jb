/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.compose.desktop.application.tasks

import org.gradle.api.file.*
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.api.tasks.Optional
import org.gradle.process.ExecResult
import org.gradle.work.ChangeType
import org.gradle.work.InputChanges
import org.jetbrains.compose.desktop.application.dsl.MacOSSigningSettings
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.internal.*
import org.jetbrains.compose.desktop.application.internal.files.*
import org.jetbrains.compose.desktop.application.internal.files.MacJarSignFileCopyingProcessor
import org.jetbrains.compose.desktop.application.internal.JvmRuntimeProperties
import org.jetbrains.compose.desktop.application.internal.validation.validate
import org.jetbrains.compose.internal.utils.*
import java.io.*
import java.nio.file.LinkOption
import java.util.*
import javax.inject.Inject
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.collections.ArrayList
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

abstract class AbstractJPackageTask @Inject constructor(
    @get:Input
    val targetFormat: TargetFormat,
) : AbstractJvmToolOperationTask("jpackage") {
    @get:InputFiles
    val files: ConfigurableFileCollection = objects.fileCollection()

    /**
     * A hack to avoid conflicts between jar files in a flat dir.
     * We receive input jar files as a list (FileCollection) of files.
     * At that point we don't have access to jar files' coordinates.
     *
     * Some files can have the same simple names.
     * For example, a project containing two modules:
     *  1. :data:utils
     *  2. :ui:utils
     * produces:
     *  1. <PROJECT>/data/utils/build/../utils.jar
     *  2. <PROJECT>/ui/utils/build/../utils.jar
     *
     *  jpackage expects all files to be in one input directory (not sure),
     *  so the natural workaround to avoid overwrites/conflicts is to add a content hash
     *  to a file name. A better solution would be to preserve coordinates or relative paths,
     *  but it is not straightforward at this point.
     *
     *  The flag is needed for two things:
     *  1. Give users the ability to turn off the mangling, if they need to preserve names;
     *  2. Proguard transformation already flattens jar files & mangles names, so we don't
     *  need to mangle twice.
     */
    @get:Input
    val mangleJarFilesNames: Property<Boolean> = objects.notNullProperty(true)

    @get:InputDirectory
    @get:Optional
    /** @see internal/wixToolset.kt */
    val wixToolsetDir: DirectoryProperty = objects.directoryProperty()

    @get:Input
    @get:Optional
    val installationPath: Property<String?> = objects.nullableProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val licenseFile: RegularFileProperty = objects.fileProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val iconFile: RegularFileProperty = objects.fileProperty()

    @get:Input
    val launcherMainClass: Property<String> = objects.notNullProperty()

    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val launcherMainJar: RegularFileProperty = objects.fileProperty()

    @get:Input
    @get:Optional
    val launcherArgs: ListProperty<String> = objects.listProperty(String::class.java)

    @get:Input
    @get:Optional
    val launcherJvmArgs: ListProperty<String> = objects.listProperty(String::class.java)

    @get:Input
    val packageName: Property<String> = objects.notNullProperty()

    @get:Input
    @get:Optional
    val packageDescription: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val packageCopyright: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val packageVendor: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val packageVersion: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val linuxShortcut: Property<Boolean?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val linuxPackageName: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val linuxAppRelease: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val linuxAppCategory: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val linuxDebMaintainer: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val linuxMenuGroup: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val linuxRpmLicenseType: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val macPackageName: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val macDockName: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val macAppStore: Property<Boolean?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val macAppCategory: Property<String?> = objects.nullableProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val macEntitlementsFile: RegularFileProperty = objects.fileProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val macRuntimeEntitlementsFile: RegularFileProperty = objects.fileProperty()

    @get:Input
    @get:Optional
    val packageBuildVersion: Property<String?> = objects.nullableProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val macProvisioningProfile: RegularFileProperty = objects.fileProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val macRuntimeProvisioningProfile: RegularFileProperty = objects.fileProperty()

    @get:Input
    @get:Optional
    val winConsole: Property<Boolean?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val winDirChooser: Property<Boolean?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val winPerUserInstall: Property<Boolean?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val winShortcut: Property<Boolean?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val winMenu: Property<Boolean?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val winMenuGroup: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val winUpgradeUuid: Property<String?> = objects.nullableProperty()

    @get:InputDirectory
    @get:Optional
    val runtimeImage: DirectoryProperty = objects.directoryProperty()

    @get:InputDirectory
    @get:Optional
    val appImage: DirectoryProperty = objects.directoryProperty()

    @get:Input
    @get:Optional
    internal val nonValidatedMacBundleID: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    internal val macExtraPlistKeysRawXml: Property<String?> = objects.nullableProperty()

    @get:InputFile
    @get:Optional
    val javaRuntimePropertiesFile: RegularFileProperty = objects.fileProperty()

    private lateinit var jvmRuntimeInfo: JvmRuntimeProperties

    @get:Optional
    @get:Nested
    internal var nonValidatedMacSigningSettings: MacOSSigningSettings? = null

    private val shouldSign: Boolean
        get() = nonValidatedMacSigningSettings?.sign?.get() == true

    private val macSigner: MacSigner? by lazy {
        val nonValidatedSettings = nonValidatedMacSigningSettings
        if (currentOS == OS.MacOS) {
            if (shouldSign) {
                val validatedSettings =
                    nonValidatedSettings!!.validate(nonValidatedMacBundleID, project, macAppStore)
                MacSignerImpl(validatedSettings, runExternalTool)
            } else NoCertificateSigner(runExternalTool)
        } else null
    }

    @get:LocalState
    protected val signDir: Provider<Directory> = project.layout.buildDirectory.dir("compose/tmp/sign")

    @get:LocalState
    protected val jpackageResources: Provider<Directory> = project.layout.buildDirectory.dir("compose/tmp/resources")

    @get:LocalState
    protected val skikoDir: Provider<Directory> = project.layout.buildDirectory.dir("compose/tmp/skiko")

    @get:Internal
    private val libsDir: Provider<Directory> = workingDir.map {
        it.dir("libs")
    }

    @get:Internal
    private val packagedResourcesDir: Provider<Directory> = libsDir.map {
        it.dir("resources")
    }

    @get:Internal
    val appResourcesDir: DirectoryProperty = objects.directoryProperty()

    /**
     * Gradle runtime verification fails,
     * if InputDirectory is not null, but a directory does not exist.
     * The directory might not exist, because prepareAppResources task
     * does not create output directory if there are no resources.
     *
     * To work around this, appResourcesDir is used as a real property,
     * but it is annotated as @Internal, so it ignored during inputs checking.
     * This property is used only for inputs checking.
     * It returns appResourcesDir value if the underlying directory exists.
     */
    @Suppress("unused")
    @get:InputDirectory
    @get:Optional
    internal val appResourcesDirInputDirHackForVerification: Provider<Directory>
        get() = appResourcesDir.map { it.takeIf { it.asFile.exists() } }

    @get:Internal
    private val libsMappingFile: Provider<RegularFile> = workingDir.map {
        it.file("libs-mapping.txt")
    }

    @get:Internal
    private val libsMapping = FilesMapping()

    override fun makeArgs(tmpDir: File): MutableList<String> = super.makeArgs(tmpDir).apply {
        fun appDir(vararg pathParts: String): String {
            /** For windows we need to pass '\\' to jpackage file, each '\' need to be escaped.
                Otherwise '$APPDIR\resources' is passed to jpackage,
                and '\r' is treated as a special character at run time.
             */
            val separator = if (currentTarget.os == OS.Windows) "\\\\" else "/"
            return listOf("${'$'}APPDIR", *pathParts).joinToString(separator) { it }
        }

        if (targetFormat == TargetFormat.AppImage || appImage.orNull == null) {
            // Args, that can only be used, when creating an app image or an installer w/o --app-image parameter
            cliArg("--input", libsDir)
            cliArg("--runtime-image", runtimeImage)
            cliArg("--resource-dir", jpackageResources)

            javaOption("-D$APP_RESOURCES_DIR=${appDir(packagedResourcesDir.ioFile.name)}")

            val mappedJar = libsMapping[launcherMainJar.ioFile]?.singleOrNull()
                ?: error("Main jar was not processed correctly: ${launcherMainJar.ioFile}")
            cliArg("--main-jar", mappedJar)
            cliArg("--main-class", launcherMainClass)

            if (currentOS == OS.Windows) {
                cliArg("--win-console", winConsole)
            }
            cliArg("--icon", iconFile)
            launcherArgs.orNull?.forEach {
                cliArg("--arguments", "'$it'")
            }
            launcherJvmArgs.orNull?.forEach {
                javaOption(it)
            }
            javaOption("-D$SKIKO_LIBRARY_PATH=${appDir()}")
            if (currentOS == OS.MacOS) {
                macDockName.orNull?.let { dockName ->
                    javaOption("-Xdock:name=$dockName")
                }
                macProvisioningProfile.orNull?.let { provisioningProfile ->
                    cliArg("--app-content", provisioningProfile)
                }
            }
        }

        if (targetFormat != TargetFormat.AppImage) {
            // Args, that can only be used, when creating an installer
            if (currentOS == OS.MacOS && jvmRuntimeInfo.majorVersion >= 18) {
                // This is needed to prevent a directory does not exist error.
                cliArg("--app-image", appImage.dir("${packageName.get()}.app"))
            } else {
                cliArg("--app-image", appImage)
            }
            cliArg("--install-dir", installationPath)
            cliArg("--license-file", licenseFile)
            cliArg("--resource-dir", jpackageResources)

            when (currentOS) {
                OS.Linux -> {
                    cliArg("--linux-shortcut", linuxShortcut)
                    cliArg("--linux-package-name", linuxPackageName)
                    cliArg("--linux-app-release", linuxAppRelease)
                    cliArg("--linux-app-category", linuxAppCategory)
                    cliArg("--linux-deb-maintainer", linuxDebMaintainer)
                    cliArg("--linux-menu-group", linuxMenuGroup)
                    cliArg("--linux-rpm-license-type", linuxRpmLicenseType)
                }
                OS.Windows -> {
                    cliArg("--win-dir-chooser", winDirChooser)
                    cliArg("--win-per-user-install", winPerUserInstall)
                    cliArg("--win-shortcut", winShortcut)
                    cliArg("--win-menu", winMenu)
                    cliArg("--win-menu-group", winMenuGroup)
                    cliArg("--win-upgrade-uuid", winUpgradeUuid)
                }
                OS.MacOS -> {}
            }
        }

        cliArg("--type", targetFormat.id)

        cliArg("--dest", destinationDir)
        cliArg("--verbose", verbose)

        cliArg("--name", packageName)
        cliArg("--description", packageDescription)
        cliArg("--copyright", packageCopyright)
        cliArg("--app-version", packageVersion)
        cliArg("--vendor", packageVendor)

        if (currentOS == OS.MacOS) {
            cliArg("--mac-package-name", macPackageName)
            cliArg("--mac-package-identifier", nonValidatedMacBundleID)
            cliArg("--mac-app-store", macAppStore)
            cliArg("--mac-app-category", macAppCategory)
            cliArg("--mac-entitlements", macEntitlementsFile)

            macSigner?.settings?.let { signingSettings ->
                cliArg("--mac-sign", true)
                cliArg("--mac-signing-key-user-name", signingSettings.identity)
                cliArg("--mac-signing-keychain", signingSettings.keychain)
                cliArg("--mac-package-signing-prefix", signingSettings.prefix)
            }
        }
    }

    private fun invalidateMappedLibs(
        inputChanges: InputChanges
    ): Set<File> {
        val outdatedLibs = HashSet<File>()
        val libsDirFile = libsDir.ioFile

        fun invalidateAllLibs() {
            outdatedLibs.addAll(files.files)

            logger.debug("Clearing all files in working dir: $libsDirFile")
            fileOperations.clearDirs(libsDirFile)
        }

        if (inputChanges.isIncremental) {
            val allChanges = inputChanges.getFileChanges(files).asSequence()

            try {
                for (change in allChanges) {
                    libsMapping.remove(change.file)?.let { files ->
                        files.forEach { fileOperations.delete(it) }
                    }
                    if (change.changeType != ChangeType.REMOVED) {
                        outdatedLibs.add(change.file)
                    }
                }
            } catch (e: Exception) {
                logger.debug("Could remove outdated libs incrementally: ${e.stacktraceToString()}")
                invalidateAllLibs()
            }
        } else {
            invalidateAllLibs()
        }

        return outdatedLibs
    }

    private fun jarCopyingProcessor(): FileCopyingProcessor =
        if (currentOS == OS.MacOS) {
            val tmpDirForSign = signDir.ioFile
            fileOperations.clearDirs(tmpDirForSign)
            MacJarSignFileCopyingProcessor(macSigner!!, tmpDirForSign, jvmRuntimeInfo.majorVersion)
        } else SimpleFileCopyingProcessor

    override fun prepareWorkingDir(inputChanges: InputChanges) {
        val libsDir = libsDir.ioFile
        val fileProcessor = jarCopyingProcessor()

        val mangleJarFilesNames = mangleJarFilesNames.get()
        fun copyFileToLibsDir(sourceFile: File): File {
            val targetName =
                if (mangleJarFilesNames && sourceFile.isJarFile) sourceFile.mangledName()
                else sourceFile.name
            val targetFile = libsDir.resolve(targetName)
            fileProcessor.copy(sourceFile, targetFile)
            return targetFile
        }

        val outdatedLibs = invalidateMappedLibs(inputChanges)
        for (sourceFile in outdatedLibs) {
            assert(sourceFile.exists()) { "Lib file does not exist: $sourceFile" }

            libsMapping[sourceFile] = if (isSkikoForCurrentOS(sourceFile)) {
                val unpackedFiles = unpackSkikoForCurrentOS(sourceFile, skikoDir.ioFile, fileOperations)
                unpackedFiles.map { copyFileToLibsDir(it) }
            } else {
                listOf(copyFileToLibsDir(sourceFile))
            }
        }

        // todo: incremental copy
        fileOperations.clearDirs(packagedResourcesDir)
        val destResourcesDir = packagedResourcesDir.ioFile
        val appResourcesDir = appResourcesDir.ioFileOrNull
        if (appResourcesDir != null) {
            for (file in appResourcesDir.walk()) {
                val relPath = file.relativeTo(appResourcesDir).path
                val destFile = destResourcesDir.resolve(relPath)
                if (file.isDirectory) {
                    fileOperations.mkdirs(destFile)
                } else {
                    file.copyTo(destFile)
                }
            }
        }

        fileOperations.clearDirs(jpackageResources)
        if (currentOS == OS.MacOS) {
            InfoPlistBuilder(macExtraPlistKeysRawXml.orNull)
                .also { setInfoPlistValues(it) }
                .writeToFile(jpackageResources.ioFile.resolve("Info.plist"))

            if (macAppStore.orNull == true) {
                val productDefPlistXml = """
                    <key>os</key>
                    <array>
                        <string>10.13</string>
                    </array>
                """.trimIndent()
                InfoPlistBuilder(productDefPlistXml)
                    .writeToFile(jpackageResources.ioFile.resolve("product-def.plist"))
            }
        }
    }

    override fun jvmToolEnvironment(): MutableMap<String, String> =
        super.jvmToolEnvironment().apply {
            if (currentOS == OS.Windows) {
                val wixDir = wixToolsetDir.ioFile
                val wixPath = wixDir.absolutePath
                val path = System.getenv("PATH") ?: ""
                put("PATH", "$wixPath;$path")
            }
        }


    override fun checkResult(result: ExecResult) {
        super.checkResult(result)
        modifyRuntimeOnMacOsIfNeeded()
        val outputFile = findOutputFileOrDir(destinationDir.ioFile, targetFormat)
        logger.lifecycle("The distribution is written to ${outputFile.canonicalPath}")
    }

    private fun modifyRuntimeOnMacOsIfNeeded() {
        if (currentOS != OS.MacOS || targetFormat != TargetFormat.AppImage) return

        val appDir = destinationDir.ioFile.resolve("${packageName.get()}.app")
        val runtimeDir = appDir.resolve("Contents/runtime")

        // Add the provisioning profile
        macRuntimeProvisioningProfile.ioFileOrNull?.copyTo(
            target = runtimeDir.resolve("Contents/embedded.provisionprofile"),
            overwrite = true
        )
        val appEntitlementsFile = macEntitlementsFile.ioFileOrNull
        val runtimeEntitlementsFile = macRuntimeEntitlementsFile.ioFileOrNull

        val macSigner = macSigner!!
        // Resign the runtime completely (and also the app dir only)
        // Sign all libs and executables in runtime
        runtimeDir.walk().forEach { file ->
            val path = file.toPath()
            if (path.isRegularFile(LinkOption.NOFOLLOW_LINKS) && (path.isExecutable() || file.name.isDylibPath)) {
                macSigner.sign(file, runtimeEntitlementsFile)
            }
        }

        macSigner.sign(runtimeDir, runtimeEntitlementsFile, forceEntitlements = true)
        macSigner.sign(appDir, appEntitlementsFile, forceEntitlements = true)
    }

    override fun initState() {
        jvmRuntimeInfo = JvmRuntimeProperties.readFromFile(javaRuntimePropertiesFile.ioFile)

        val mappingFile = libsMappingFile.ioFile
        if (mappingFile.exists()) {
            try {
                libsMapping.loadFrom(mappingFile)
            } catch (e: Exception) {
                fileOperations.delete(mappingFile)
                throw e
            }
            logger.debug("Loaded libs mapping from $mappingFile")
        }
    }

    override fun saveStateAfterFinish() {
        val mappingFile = libsMappingFile.ioFile
        libsMapping.saveTo(mappingFile)
        logger.debug("Saved libs mapping to $mappingFile")
    }

    private fun setInfoPlistValues(plist: InfoPlistBuilder) {
        check(currentOS == OS.MacOS) { "Current OS is not macOS: $currentOS" }

        plist[PlistKeys.LSMinimumSystemVersion] = "10.13"
        plist[PlistKeys.CFBundleDevelopmentRegion] = "English"
        plist[PlistKeys.CFBundleAllowMixedLocalizations] = "true"
        val packageName = packageName.get()
        plist[PlistKeys.CFBundleExecutable] = packageName
        plist[PlistKeys.CFBundleIconFile] = "$packageName.icns"
        val bundleId = nonValidatedMacBundleID.orNull
            ?: launcherMainClass.get().substringBeforeLast(".")
        plist[PlistKeys.CFBundleIdentifier] = bundleId
        plist[PlistKeys.CFBundleInfoDictionaryVersion] = "6.0"
        plist[PlistKeys.CFBundleName] = packageName
        plist[PlistKeys.CFBundlePackageType] = "APPL"
        val packageVersion = packageVersion.get()!!
        plist[PlistKeys.CFBundleShortVersionString] = packageVersion
        // If building for the App Store, use "utilities" as default just like jpackage.
        val category = macAppCategory.orNull ?: (if (macAppStore.orNull == true) "public.app-category.utilities" else null)
        plist[PlistKeys.LSApplicationCategoryType] = category ?: "Unknown"
        val packageBuildVersion = packageBuildVersion.orNull ?: packageVersion
        plist[PlistKeys.CFBundleVersion] = packageBuildVersion
        val year = Calendar.getInstance().get(Calendar.YEAR)
        plist[PlistKeys.NSHumanReadableCopyright] = packageCopyright.orNull
            ?: "Copyright (C) $year"
        plist[PlistKeys.NSSupportsAutomaticGraphicsSwitching] = "true"
        plist[PlistKeys.NSHighResolutionCapable] = "true"
    }
}

// Serializable is only needed to avoid breaking configuration cache:
// https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:requirements
private class FilesMapping : Serializable {
    private var mapping = HashMap<File, List<File>>()

    operator fun get(key: File): List<File>? =
        mapping[key]

    operator fun set(key: File, value: List<File>) {
        mapping[key] = value
    }

    fun remove(key: File): List<File>? =
        mapping.remove(key)

    fun loadFrom(mappingFile: File) {
        mappingFile.readLines().forEach { line ->
            if (line.isNotBlank()) {
                val paths = line.splitToSequence(File.pathSeparatorChar)
                val lib = File(paths.first())
                val mappedFiles = paths.drop(1).mapTo(ArrayList()) { File(it) }
                mapping[lib] = mappedFiles
            }
        }
    }

    fun saveTo(mappingFile: File) {
        mappingFile.parentFile.mkdirs()
        mappingFile.bufferedWriter().use { writer ->
            mapping.entries
                .sortedBy { (k, _) -> k.absolutePath }
                .forEach { (k, values) ->
                    (sequenceOf(k) + values.asSequence())
                        .joinTo(writer, separator = File.pathSeparator, transform = { it.absolutePath })
                }
        }
    }

    private fun writeObject(stream: ObjectOutputStream) {
        stream.writeObject(mapping)
    }

    private fun readObject(stream: ObjectInputStream) {
        mapping = stream.readObject() as HashMap<File, List<File>>
    }
}

private fun isSkikoForCurrentOS(lib: File): Boolean =
    lib.name.startsWith("skiko-awt-runtime-${currentOS.id}-${currentArch.id}")
            && lib.name.endsWith(".jar")

private fun unpackSkikoForCurrentOS(sourceJar: File, skikoDir: File, fileOperations: FileSystemOperations): List<File> {
    val entriesToUnpack = when (currentOS) {
        OS.MacOS -> setOf("libskiko-macos-${currentArch.id}.dylib")
        OS.Windows -> setOf("skiko-windows-${currentArch.id}.dll", "icudtl.dat")
        OS.Linux -> setOf("libskiko-linux-${currentArch.id}.so")
    }

    // output files: unpacked libs, corresponding .sha256 files, and target jar
    val outputFiles = ArrayList<File>(entriesToUnpack.size * 2 + 1)
    val targetJar = skikoDir.resolve(sourceJar.name)
    outputFiles.add(targetJar)

    fileOperations.clearDirs(skikoDir)
    transformJar(sourceJar, targetJar) { entry, zin, zout ->
        // check both entry or entry.sha256
        if (entry.name.removeSuffix(".sha256") in entriesToUnpack) {
            val unpackedFile = skikoDir.resolve(entry.name.substringAfterLast("/"))
            zin.copyTo(unpackedFile)
            outputFiles.add(unpackedFile)
        } else {
            copyZipEntry(entry, zin, zout)
        }
    }
    return outputFiles
}
