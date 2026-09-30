/*
 * Compile-time feature module selection (shared by :ajiriwa and :psbill).
 *
 * The applying app sets, BEFORE `apply(from = ...)`:
 *   extra["featureModules.app"]        = "ajiriwa"                  // key prefix in modules.properties
 *   extra["featureModules.catalogue"]  = listOf("dashboard", ...)   // every selectable module
 *   extra["featureModules.builtIn"]    = listOf("wifi", ...)        // modules whose code lives in core (flag only)
 *   extra["featureModules.package"]    = "com.example.psbill.core"  // package of the generated registry
 *   extra["featureModules.featurePkg"] = "com.example.psbill.modules"
 *   extra["featureModules.baseClass"]  = "FeatureModule"            // type of the registry's `features` list
 *
 * Selection (first match wins):
 *   1. -P<app>.modules=sms,orders       exact list, e.g. for CI
 *   2. <root>/modules.properties        <app>.<module>=true|false (missing = true)
 *
 * Outputs (read back by the app build script):
 *   extra["featureModules.enabled"]    List<String> of enabled modules
 *   extra["featureModules.genSrc"]     dir with the generated CompiledModules.kt
 *   extra["featureModules.manifest"]   merged manifest overlay of enabled modules (or null)
 */

import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

val fmApp = extra["featureModules.app"] as String
@Suppress("UNCHECKED_CAST")
val fmCatalogue = extra["featureModules.catalogue"] as List<String>
@Suppress("UNCHECKED_CAST")
val fmBuiltIn = (if (extra.has("featureModules.builtIn")) extra["featureModules.builtIn"] as List<String> else emptyList())
val fmPackage = extra["featureModules.package"] as String
val fmFeaturePkg = extra["featureModules.featurePkg"] as String
val fmBaseClass = extra["featureModules.baseClass"] as String

// ── 1. Resolve the selection ─────────────────────────────────────────────────
val propsFile = rootProject.file("modules.properties")
val cliList = (findProperty("$fmApp.modules") as String?)?.trim()

val fmEnabled: List<String> = if (!cliList.isNullOrEmpty()) {
    val wanted = cliList.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val unknown = wanted - fmCatalogue.toSet()
    if (unknown.isNotEmpty()) throw GradleException("-P$fmApp.modules: unknown module(s) $unknown. Known: $fmCatalogue")
    fmCatalogue.filter { it in wanted }
} else {
    val props = Properties()
    if (propsFile.exists()) propsFile.inputStream().use { props.load(it) }
    val prefix = "$fmApp."
    props.stringPropertyNames().filter { it.startsWith(prefix) }.forEach { key ->
        val module = key.removePrefix(prefix)
        if (module !in fmCatalogue) logger.warn("modules.properties: '$key' is not a known $fmApp module (known: $fmCatalogue)")
    }
    fmCatalogue.filter { module ->
        when (props.getProperty("$prefix$module")?.trim()?.lowercase()) {
            null, "", "true", "yes", "1", "on", "x" -> true
            else -> false
        }
    }
}
if (fmEnabled.isEmpty()) throw GradleException("No $fmApp modules selected — tick at least one in modules.properties")
logger.lifecycle("[$fmApp] compiling modules: ${fmEnabled.joinToString()}  (skipped: ${(fmCatalogue - fmEnabled.toSet()).joinToString().ifEmpty { "none" }})")

// ── 2. Generate the registry ─────────────────────────────────────────────────
fun pascal(s: String) = s.split('_', '-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

fun writeIfChanged(file: File, text: String) {
    if (!file.exists() || file.readText() != text) {
        file.parentFile.mkdirs()
        file.writeText(text)
    }
}

val genRoot = layout.buildDirectory.dir("generated/featureModules").get().asFile
val genSrc = File(genRoot, "kotlin")
val featureModules = fmEnabled.filter { it !in fmBuiltIn }
val registry = buildString {
    appendLine("// GENERATED from modules.properties by gradle/feature-modules.gradle.kts — do not edit.")
    appendLine("package $fmPackage")
    appendLine()
    appendLine("object CompiledModules {")
    appendLine("    /** Every module compiled into this build. */")
    appendLine("    val keys: Set<String> = setOf(${fmEnabled.joinToString { "\"$it\"" }})")
    appendLine()
    appendLine("    /** Feature modules (in src/module/<slug>) compiled into this build. */")
    appendLine("    val features: List<$fmBaseClass> = listOf(")
    featureModules.forEach { appendLine("        $fmFeaturePkg.$it.${pascal(it)}Feature,") }
    appendLine("    )")
    appendLine()
    appendLine("    fun has(module: String): Boolean = module in keys")
    appendLine("}")
}
writeIfChanged(File(genSrc, fmPackage.replace('.', '/') + "/CompiledModules.kt"), registry)

// ── 3. Merge module manifest fragments into one overlay ──────────────────────
val fragments = featureModules.map { project.file("src/module/$it/AndroidManifest.xml") }.filter { it.exists() }
val overlay = File(genRoot, "AndroidManifest.xml")
if (fragments.isNotEmpty()) {
    val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    val out = dbf.newDocumentBuilder().newDocument()
    val androidNs = "http://schemas.android.com/apk/res/android"
    val toolsNs = "http://schemas.android.com/tools"
    val root = out.createElement("manifest")
    root.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:android", androidNs)
    root.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:tools", toolsNs)
    out.appendChild(root)
    val app = out.createElement("application")
    fragments.forEach { f ->
        val doc = dbf.newDocumentBuilder().parse(f)
        val kids = doc.documentElement.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
            if (n.nodeName == "application") {
                val appKids = n.childNodes
                for (j in 0 until appKids.length) {
                    val c = appKids.item(j)
                    if (c.nodeType == org.w3c.dom.Node.ELEMENT_NODE) app.appendChild(out.importNode(c, true))
                }
            } else {
                root.appendChild(out.importNode(n, true))
            }
        }
    }
    if (app.hasChildNodes()) root.appendChild(app)
    val sw = java.io.StringWriter()
    TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.INDENT, "yes")
        setOutputProperty(OutputKeys.ENCODING, "utf-8")
    }.transform(DOMSource(out), StreamResult(sw))
    writeIfChanged(overlay, sw.toString())
    extra["featureModules.manifest"] = overlay
} else {
    extra["featureModules.manifest"] = null
}

extra["featureModules.enabled"] = fmEnabled
extra["featureModules.genSrc"] = genSrc

