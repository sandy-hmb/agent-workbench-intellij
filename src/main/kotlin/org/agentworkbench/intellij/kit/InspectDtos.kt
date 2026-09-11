package org.agentworkbench.intellij.kit

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.Instant

internal data class InspectResponse(
    val operation: String, val status: String, val observedAt: Instant, val root: String?, val revision: String?, val data: JsonElement?, val diagnostics: List<InspectDiagnostic>,
)
internal data class InspectDiagnostic(
    val code: String,
    val message: String,
    val severity: String? = null,
    val path: String? = null,
    val line: Int? = null,
)

private fun JsonObject.text(name: String): String = get(name)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    ?: error("Inspect 缺少 $name")
private fun JsonObject.number(name: String): Int = get(name)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
    ?.asBigDecimal?.intValueExact() ?: error("Inspect 缺少 $name")
private fun JsonObject.array(name: String) = get(name)
    ?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: error("Inspect 缺少 $name")
private fun JsonObject.objectValue(name: String) = get(name)
    ?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: error("Inspect 缺少 $name")

internal data class HandoffSource(
    val kind: String,
    val path: String,
    val revision: String,
    val startLine: Int,
)

internal data class HandoffData(
    val slug: String,
    val content: String,
    val estimatedTokens: Int,
    val sources: List<HandoffSource>,
) {
    companion object {
        fun parse(data: JsonElement?): HandoffData {
            val value = data?.takeIf(JsonElement::isJsonObject)?.asJsonObject
                ?: error("Inspect handoff 缺少 data")
            return HandoffData(
                value.text("slug"),
                value.text("content"),
                value.number("estimatedTokens"),
                value.array("sources").map { element ->
                    val source = element.takeIf(JsonElement::isJsonObject)?.asJsonObject
                        ?: error("Inspect handoff source 无效")
                    HandoffSource(
                        source.text("kind"),
                        source.text("path"),
                        source.text("revision"),
                        source.number("startLine"),
                    )
                },
            )
        }
    }
}

internal data class SearchHit(
    val slug: String,
    val title: String,
    val status: String,
    val lastUpdated: String,
    val path: String,
    val line: Int,
    val heading: String?,
    val snippet: String,
)

internal data class SearchData(
    val query: String,
    val items: List<SearchHit>,
    val total: Int,
    val hasMore: Boolean,
    val incomplete: Boolean = false,
) {
    companion object {
        fun parse(data: JsonElement?): SearchData {
            val value = data?.takeIf(JsonElement::isJsonObject)?.asJsonObject
                ?: error("Inspect search 缺少 data")
            val page = value.objectValue("page")
            return SearchData(
                value.text("query"),
                value.array("items").map { element ->
                    val hit = element.takeIf(JsonElement::isJsonObject)?.asJsonObject
                        ?: error("Inspect search item 无效")
                    SearchHit(
                        hit.text("slug"),
                        hit.text("title"),
                        hit.text("status"),
                        hit.text("lastUpdated"),
                        hit.text("path"),
                        hit.number("line"),
                        hit.get("heading")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString,
                        hit.text("snippet"),
                    )
                },
                page.number("total"),
                page.get("hasMore")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                    ?: error("Inspect search page 缺少 hasMore"),
            )
        }
    }
}

internal object InspectProtocol {
    fun parse(text: String, expectedOperation: String, expectedRoot: String): Result<InspectResponse> = runCatching {
        val envelope = com.google.gson.JsonParser.parseString(text).takeIf(JsonElement::isJsonObject)?.asJsonObject ?: error("Inspect 信封格式无效")
        val version = envelope.requiredObject("apiVersion")
        require(version.requiredInt("major") == 1) { "Inspect 主版本不兼容" }; version.requiredInt("minor")
        require(envelope.requiredString("operation") == expectedOperation) { "Inspect operation 不匹配" }
        val status = envelope.requiredString("status"); require(status in setOf("ok", "partial", "error")) { "Inspect 状态未知" }
        val observedAt = Instant.parse(envelope.requiredString("observedAt"))
        val diagnostics = envelope.requiredArray("diagnostics").map { item ->
            val diagnostic = item.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: error("Inspect 诊断格式无效")
            InspectDiagnostic(
                diagnostic.requiredString("code"),
                diagnostic.requiredString("message"),
                diagnostic.nullableString("severity"),
                diagnostic.nullableString("path"),
                diagnostic.nullableInt("line"),
            )
        }
        val root = envelope.nullableString("root")
        val revision = envelope.nullableString("revision")
        val data = envelope.get("data")?.takeUnless(JsonElement::isJsonNull)
        if (status != "error") {
            require(root == expectedRoot) { "Inspect 根目录不匹配" }
            require(!revision.isNullOrBlank()) { "Inspect 缺少 revision" }
            val payload = data?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: error("Inspect 缺少 data")
            validate(expectedOperation, payload)
        } else require(diagnostics.isNotEmpty()) { "Inspect error 缺少诊断" }
        InspectResponse(expectedOperation, status, observedAt, root, revision, data, diagnostics)
    }

    private fun validate(operation: String, data: JsonObject) = when (operation) {
        "workspace" -> { data.requiredString("mode"); data.requiredObject("identity"); data.requiredArray("repositories").forEach(::repository); data.requiredObject("localContext"); data.requiredObject("configuration"); data.requiredObject("protocol") }
        "features", "runs" -> { val page = data.requiredObject("page"); require(page.requiredInt("limit") in 1..200) { "Inspect page limit 无效" }; data.requiredArray("items") }
        "search" -> { val page = data.requiredObject("page"); require(page.requiredInt("limit") in 1..50) { "Inspect search page limit 无效" }; data.requiredString("query"); data.requiredArray("items"); SearchData.parse(data) }
        "feature" -> { data.requiredObject("summary"); data.requiredArray("tasks"); data.requiredArray("files"); data.requiredArray("artifacts"); data.requiredObject("progression"); data.requiredString("featureRevision") }
        "handoff" -> { data.requiredObject("progression"); data.requiredString("featureRevision"); HandoffData.parse(data) }
        "document" -> { data.requiredString("path"); data.requiredString("revision"); data.requiredString("content") }
        "verification" -> { data.requiredString("slug"); data.requiredString("featureRevision"); data.requiredArray("batches"); data.requiredArray("repositoryStates"); data.requiredString("applicability") }
        "workflow" -> { data.requiredString("configState"); data.requiredArray("extensions") }
        "run" -> { data.requiredString("id"); data.requiredArray("records") }
        else -> error("不支持的 operation")
    }

    private fun repository(element: JsonElement) {
        val item = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: error("仓库格式无效")
        item.requiredString("id"); item.requiredString("absolutePath")
    }
    private fun JsonObject.requiredObject(name: String) = get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: error("Inspect 缺少 $name")
    private fun JsonObject.requiredArray(name: String) = get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: error("Inspect 缺少 $name")
    private fun JsonObject.requiredString(name: String): String = nullableString(name) ?: error("Inspect 缺少 $name")
    private fun JsonObject.requiredInt(name: String): Int {
        val value = get(name) ?: error("Inspect 缺少 $name"); require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Inspect $name 类型无效" }; return value.asBigDecimal.intValueExact()
    }
    private fun JsonObject.nullableString(name: String) = get(name)?.takeUnless(JsonElement::isJsonNull)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.nullableInt(name: String): Int? = get(name)?.takeUnless(JsonElement::isJsonNull)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.runCatching { asBigDecimal.intValueExact() }?.getOrNull()
}
