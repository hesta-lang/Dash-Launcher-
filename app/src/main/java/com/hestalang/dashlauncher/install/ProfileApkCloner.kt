package com.hestalang.dashlauncher.install

import android.R
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.android.apksig.KeyConfig
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

/**
 * Builds a private, profile-specific APK instance.
 *
 * The input APK is never modified. The resulting APK has a new Android package identity,
 * a stable Dash Launcher signing identity, and is stored beside the selected profile.
 *
 * This implementation rewrites the binary manifest and resource-table package identity. It
 * deliberately does not rewrite DEX/native code: package-name occurrences there are reported
 * as warnings instead of being blindly changed, because arbitrary code rewriting can break apps.
 */
class ProfileApkCloner {

    fun build(
        sourceApk: File,
        outputApk: File,
        newPackageName: String,
        displayLabel: String
    ): ProfileApkCloneResult {
        require(sourceApk.isFile && sourceApk.canRead()) {
            "No se puede leer el APK original."
        }
        require(sourceApk.absoluteFile != outputApk.absoluteFile) {
            "El APK de salida no puede sobrescribir el archivo original."
        }
        require(PACKAGE_PATTERN.matches(newPackageName)) {
            "El identificador generado no es válido: " + newPackageName
        }
        require(displayLabel.isNotBlank() && displayLabel.length <= 80) {
            "El nombre visible de la instancia no es válido."
        }

        val sourcePackage = readPackageName(sourceApk)
        require(sourcePackage.isNotBlank()) {
            "El APK no declara un identificador de paquete válido."
        }
        require(sourcePackage != newPackageName) {
            "El identificador nuevo coincide con el del APK original."
        }

        val outputDirectory = outputApk.absoluteFile.parentFile
            ?: throw IllegalArgumentException("La ruta de salida no es válida.")
        if (!outputDirectory.exists()) {
            check(outputDirectory.mkdirs()) {
                "No se pudo crear la carpeta de la instancia."
            }
        }

        val unsigned = File(outputDirectory, outputApk.name + ".unsigned.tmp")
        val signed = File(outputDirectory, outputApk.name + ".signed.tmp")
        val warnings = findHardCodedPackageReferences(sourceApk, sourcePackage)

        try {
            ApkModule.loadApkFile(sourceApk).use { module ->
                module.setLoadDefaultFramework(false)
                val manifest = module.androidManifest
                    ?: throw IllegalArgumentException("El APK no contiene AndroidManifest.xml.")

                rewriteManifest(
                    module = module,
                    manifest = manifest,
                    oldPackage = sourcePackage,
                    newPackage = newPackageName,
                    displayLabel = displayLabel
                )

                module.refreshManifest()
                module.refreshTable()
                module.writeApk(unsigned)
            }

            signAndVerify(unsigned, signed)
            moveReplacing(signed, outputApk)

            return ProfileApkCloneResult(
                outputApk = outputApk,
                sourcePackageName = sourcePackage,
                installedPackageName = newPackageName,
                warnings = warnings
            )
        } finally {
            unsigned.delete()
            signed.delete()
        }
    }

    private fun readPackageName(apk: File): String {
        ApkModule.loadApkFile(apk).use { module ->
            val manifest = module.androidManifest
                ?: throw IllegalArgumentException("El APK no contiene AndroidManifest.xml.")
            return manifest.packageName
                ?: throw IllegalArgumentException("No se pudo leer el paquete del APK.")
        }
    }

    private fun rewriteManifest(
        module: ApkModule,
        manifest: com.reandroid.arsc.chunk.xml.AndroidManifestBlock,
        oldPackage: String,
        newPackage: String,
        displayLabel: String
    ) {
        val elements = manifest.recursive(ResXmlElement::class.java).asSequence().toList()

        // Preserve Java class names before changing the manifest package namespace.
        for (element in elements) {
            val tag = element.name ?: continue
            if (tag !in COMPONENT_TAGS) continue
            for (attribute in element.getAttributes().asSequence().toList()) {
                if (attributeKey(attribute) != "name") continue
                val value = stringValue(attribute) ?: continue
                expandClassName(value, oldPackage)?.let(attribute::setValueAsString)
            }
        }

        // Rebase authorities, permissions, process names and task affinities to this instance.
        for (element in elements) {
            val tag = element.name ?: continue
            for (attribute in element.getAttributes().asSequence().toList()) {
                val key = attributeKey(attribute) ?: continue
                val value = stringValue(attribute) ?: continue
                when {
                    key == "authorities" -> {
                        val changed = value.split(';').map { authority ->
                            replacePackagePrefix(authority.trim(), oldPackage, newPackage)
                                ?: authority.trim()
                        }
                        if (changed.joinToString(";") != value) {
                            attribute.setValueAsString(changed.joinToString(";"))
                        }
                    }

                    key == "name" && tag in PERMISSION_TAGS -> {
                        replacePackagePrefix(value, oldPackage, newPackage)
                            ?.let(attribute::setValueAsString)
                    }

                    key in PACKAGE_PREFIX_ATTRIBUTES -> {
                        replacePackagePrefix(value, oldPackage, newPackage)
                            ?.let(attribute::setValueAsString)
                    }

                    key == "process" && !value.startsWith(":") -> {
                        replacePackagePrefix(value, oldPackage, newPackage)
                            ?.let(attribute::setValueAsString)
                    }
                }
            }
        }

        // sharedUserId cannot be retained with a different signing identity.
        manifest.documentElement?.removeAttributesWithId(R.attr.sharedUserId)
        manifest.documentElement?.removeAttributesWithId(R.attr.sharedUserLabel)

        // Updates both the binary manifest and resources.arsc package name.
        module.setPackageName(newPackage)
        manifest.setApplicationLabel(displayLabel)

        // An explicit launcher-activity label overrides the application label.
        for (activity in launcherActivities(manifest)) {
            if (activity.searchAttributeByResourceId(R.attr.label) != null) {
                activity.getOrCreateAndroidAttribute("label", R.attr.label)
                    .setValueAsString(displayLabel)
            }
        }
    }

    private fun signAndVerify(unsignedApk: File, outputApk: File) {
        val identity = signingIdentity()
        val signer = ApkSigner.SignerConfig.Builder(
            SIGNER_NAME,
            KeyConfig.Jca(identity.first),
            listOf(identity.second)
        ).build()

        ApkSigner.Builder(listOf(signer))
            .setInputApk(unsignedApk)
            .setOutputApk(outputApk)
            .setMinSdkVersion(SIGNING_MIN_SDK)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setCreatedBy("Dash Launcher")
            .build()
            .sign()

        val verification = ApkVerifier.Builder(outputApk)
            .setMinCheckedPlatformVersion(SIGNING_MIN_SDK)
            .build()
            .verify()

        check(verification.isVerified) {
            "La firma del APK modificado no superó la verificación: " +
                verification.allErrors.take(4).joinToString("; ")
        }
    }

    /**
     * The same Android Keystore key is reused for every profile instance. Rebuilding an APK
     * for an existing profile therefore updates that package instead of replacing its data.
     */
    private fun signingIdentity(): Pair<PrivateKey, X509Certificate> {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(SIGNING_KEY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                ANDROID_KEYSTORE
            )
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    SIGNING_KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generator.generateKeyPair()
        }

        val privateKey = keyStore.getKey(SIGNING_KEY_ALIAS, null) as? PrivateKey
            ?: throw IllegalStateException("No se pudo recuperar la clave de firma de Dash Launcher.")
        val certificate = keyStore.getCertificate(SIGNING_KEY_ALIAS) as? X509Certificate
            ?: throw IllegalStateException("No se pudo recuperar el certificado de firma.")
        return privateKey to certificate
    }

    /**
     * Flags package strings left in DEX or native libraries. They may be harmless or may be
     * used by code that expects the original package name.
     */
    private fun findHardCodedPackageReferences(apk: File, packageName: String): List<String> {
        val warnings = mutableListOf<String>()
        val needle = packageName.toByteArray(Charsets.UTF_8)
        val slashNeedle = packageName.replace('.', '/').toByteArray(Charsets.UTF_8)

        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory ||
                    !(entry.name.matches(DEX_ENTRY) || entry.name.endsWith(".so"))
                ) continue
                if (entry.size <= 0L || entry.size > MAX_SCAN_ENTRY_BYTES) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                if (contains(bytes, needle) || contains(bytes, slashNeedle)) {
                    warnings += if (entry.name.endsWith(".so")) {
                        "Se detectó el paquete original dentro de " + entry.name +
                            "; una biblioteca nativa podría esperar la identidad original."
                    } else {
                        "Se detectó el paquete original dentro de " + entry.name +
                            "; algunas funciones podrían depender del identificador original."
                    }
                }
            }
        }
        return warnings.distinct()
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        for (start in 0..(haystack.size - needle.size)) {
            var matches = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun launcherActivities(
        manifest: com.reandroid.arsc.chunk.xml.AndroidManifestBlock
    ): List<ResXmlElement> =
        manifest.recursive(ResXmlElement::class.java).asSequence()
            .filter { it.name == "activity" || it.name == "activity-alias" }
            .filter { activity ->
                activity.recursive(ResXmlElement::class.java).asSequence()
                    .filter { it.name == "intent-filter" }
                    .any { filter ->
                        filter.recursive(ResXmlElement::class.java).asSequence().any { child ->
                            val name = child.searchAttributeByResourceId(R.attr.name)
                                ?.let(::stringValue)
                            (child.name == "action" && name == "android.intent.action.MAIN") ||
                                (child.name == "category" && name == "android.intent.category.LAUNCHER")
                        }
                    }
            }
            .toList()

    private fun attributeKey(attribute: ResXmlAttribute): String? = when (attribute.nameId) {
        R.attr.name -> "name"
        R.attr.authorities -> "authorities"
        R.attr.permission -> "permission"
        R.attr.readPermission -> "readPermission"
        R.attr.writePermission -> "writePermission"
        R.attr.targetPackage -> "targetPackage"
        R.attr.taskAffinity -> "taskAffinity"
        R.attr.process -> "process"
        else -> attribute.name?.takeIf { it.isNotBlank() }
    }

    private fun stringValue(attribute: ResXmlAttribute): String? =
        if (attribute.valueType == ValueType.STRING) attribute.valueAsString else null

    private fun expandClassName(value: String, packageName: String): String? = when {
        value.startsWith(".") -> packageName + value
        value.isNotEmpty() && !value.contains('.') -> packageName + "." + value
        else -> null
    }

    private fun replacePackagePrefix(value: String, oldPackage: String, newPackage: String): String? = when {
        value == oldPackage -> newPackage
        value.startsWith(oldPackage + ".") -> newPackage + value.substring(oldPackage.length)
        else -> null
    }

    private fun moveReplacing(source: File, target: File) {
        target.parentFile?.let { parent ->
            if (!parent.exists()) check(parent.mkdirs()) {
                "No se pudo crear la carpeta de salida."
            }
        }
        if (target.exists() && !target.delete()) {
            throw IllegalStateException("No se pudo reemplazar el APK de esta instancia.")
        }
        check(source.renameTo(target)) {
            "No se pudo guardar el APK de la instancia."
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val SIGNING_KEY_ALIAS = "dash_launcher_profile_apk_signing_v1"
        private const val SIGNER_NAME = "dash-launcher-profile"
        private const val SIGNING_MIN_SDK = 23
        private const val MAX_SCAN_ENTRY_BYTES = 96L * 1024L * 1024L
        private val PACKAGE_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
        private val DEX_ENTRY = Regex("classes(?:[2-9][0-9]*)?\\.dex")

        private val COMPONENT_TAGS = setOf(
            "application", "activity", "activity-alias", "service", "receiver",
            "provider", "instrumentation"
        )
        private val PERMISSION_TAGS = setOf(
            "permission", "permission-group", "permission-tree",
            "uses-permission", "uses-permission-sdk-23"
        )
        private val PACKAGE_PREFIX_ATTRIBUTES = setOf(
            "permission", "readPermission", "writePermission", "targetPackage", "taskAffinity"
        )
    }
}

data class ProfileApkCloneResult(
    val outputApk: File,
    val sourcePackageName: String,
    val installedPackageName: String,
    val warnings: List<String>
)
