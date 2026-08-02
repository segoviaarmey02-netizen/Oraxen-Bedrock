package dev.oraxenbedrock.conversion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.oraxenbedrock.io.PackSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves modern 1.21.4+ item definitions. Conditions which have an
 * equivalent Geyser v2 predicate are retained as separate visual variants;
 * unsupported display-context conditions use their normal/fallback branch.
 */
final class JavaItemModelResolver {
    record Variant(String model, List<JsonObject> predicates) {}
    record Result(String model, boolean definitionFound, List<Variant> variants,
                  List<String> guiModels, List<String> warnings) {}
    private record Candidate(String model, List<JsonObject> predicates,
                             boolean baseline, boolean compositeLayer) {}

    private final PackSource pack;
    private final String defaultNamespace;

    JavaItemModelResolver(PackSource pack, String defaultNamespace) {
        this.pack = pack;
        this.defaultNamespace = defaultNamespace;
    }

    Result resolve(String reference) throws IOException {
        if (reference == null || reference.isBlank())
            return new Result(null, false, List.of(), List.of(), List.of());
        String normalized = normalize(reference);
        int colon = normalized.indexOf(':');
        Path definition = pack.findAsset(normalized.substring(0, colon),
                "items/" + normalized.substring(colon + 1) + ".json");
        if (definition == null || !Files.isRegularFile(definition))
            return new Result(null, false, List.of(), List.of(), List.of());

        JsonObject root = JsonSupport.readObject(definition);
        JsonElement node = root.has("model") ? root.get("model") : root;
        List<String> warnings = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        Set<String> guiModels = new LinkedHashSet<>();
        collectGuiModels(node, guiModels, 0, false);
        collect(node, List.of(), true, false, candidates, warnings, 0);
        Candidate baseline = candidates.stream().filter(Candidate::baseline)
                .findFirst().orElse(candidates.isEmpty() ? null : candidates.get(0));
        String model = baseline == null ? null : baseline.model();
        if (baseline == null)
            warnings.add("Modern item definition has no convertible model: " + normalized);
        Set<String> seen = new LinkedHashSet<>();
        List<Variant> variants = new ArrayList<>();
        if (model != null) seen.add(model + "\n[]");
        for (Candidate candidate : candidates) {
            if ((!candidate.compositeLayer()
                    && (candidate.predicates().isEmpty()
                    || candidate.model().equals(model)))
                    || candidate.model().equals(model)
                    && candidate.predicates().isEmpty()) continue;
            String key = candidate.model() + "\n" + candidate.predicates();
            if (seen.add(key))
                variants.add(new Variant(candidate.model(),
                        candidate.predicates().stream().map(JsonObject::deepCopy).toList()));
        }
        return new Result(model, true, List.copyOf(variants),
                List.copyOf(guiModels), List.copyOf(warnings));
    }

    /**
     * Bedrock has no display-context mapping predicate. The GUI branch is
     * still useful as the inventory icon, while the normal fallback remains
     * the held/attachable model. This follows only the baseline path through
     * other selectors so unrelated damaged/charged states are not composited
     * into the default icon.
     */
    private void collectGuiModels(
            JsonElement node, Set<String> output, int depth,
            boolean insideGuiBranch) {
        if (node == null || node.isJsonNull() || depth > 32
                || !node.isJsonObject()) return;
        JsonObject object = node.getAsJsonObject();
        String type = primitive(object.get("type"));
        String shortType = type == null ? ""
                : type.substring(type.indexOf(':') + 1)
                .toLowerCase(Locale.ROOT);
        switch (shortType) {
            case "model" -> {
                String model = primitive(object.get("model"));
                if (insideGuiBranch && model != null)
                    output.add(normalize(model));
            }
            case "composite" -> {
                JsonArray models = array(object.get("models"));
                if (models != null)
                    models.forEach(value ->
                            collectGuiModels(value, output, depth + 1,
                                    insideGuiBranch));
            }
            case "special" -> {
                JsonElement base = object.get("base");
                String reference = primitive(base);
                if (insideGuiBranch && reference != null)
                    output.add(normalize(reference));
                else collectGuiModels(base, output, depth + 1,
                        insideGuiBranch);
            }
            case "select" -> {
                if (shortProperty(property(object)).equals("display_context")) {
                    JsonArray cases = array(object.get("cases"));
                    if (cases != null) for (JsonElement value : cases) {
                        if (!value.isJsonObject()) continue;
                        JsonObject entry = value.getAsJsonObject();
                        if (strings(entry.get("when")).stream()
                                .map(valueName ->
                                        valueName.toLowerCase(Locale.ROOT))
                                .anyMatch("gui"::equals))
                            collectGuiModels(
                                    entry.get("model"), output, depth + 1, true);
                    }
                    // A fallback can itself contain a nested display-context
                    // selector, but it is not a GUI icon merely because it is
                    // the outer selector's normal branch.
                    collectGuiModels(object.get("fallback"), output,
                            depth + 1, false);
                    return;
                }
                collectGuiModels(
                        fallbackOrFirst(object, "cases"), output, depth + 1,
                        insideGuiBranch);
            }
            case "condition" -> collectGuiModels(
                    object.has("on_false")
                            ? object.get("on_false") : object.get("on_true"),
                    output, depth + 1, insideGuiBranch);
            case "range_dispatch" -> collectGuiModels(
                    fallbackOrFirst(object, "entries"), output, depth + 1,
                    insideGuiBranch);
            default -> {
                JsonElement model = object.get("model");
                if (model != null)
                    collectGuiModels(model, output, depth + 1,
                            insideGuiBranch);
            }
        }
    }

    private void collect(JsonElement node, List<JsonObject> predicates,
                         boolean baseline, boolean compositeLayer,
                         List<Candidate> output,
                         List<String> warnings, int depth) {
        if (node == null || node.isJsonNull() || depth > 32) return;
        if (node.isJsonArray()) {
            boolean first = true;
            for (JsonElement child : node.getAsJsonArray()) {
                collect(child, predicates, baseline && first, compositeLayer,
                        output, warnings, depth + 1);
                first = false;
            }
            return;
        }
        if (!node.isJsonObject()) return;

        JsonObject object = node.getAsJsonObject();
        String type = primitive(object.get("type"));
        String shortType = type == null ? "" : type.substring(type.indexOf(':') + 1)
                .toLowerCase(Locale.ROOT);
        if (shortType.equals("model")) {
            String model = primitive(object.get("model"));
            if (model != null)
                output.add(new Candidate(normalize(model), copy(predicates),
                        baseline, compositeLayer));
            return;
        }

        switch (shortType) {
            case "condition" -> {
                JsonObject predicate = conditionPredicate(object);
                if (predicate == null) {
                    warnings.add("Unsupported Java item condition '"
                            + property(object) + "' uses its normal branch");
                    JsonElement normal = object.has("on_false")
                            ? object.get("on_false") : object.get("on_true");
                    collect(normal, predicates, baseline, compositeLayer,
                            output, warnings, depth + 1);
                    return;
                }
                collect(object.get("on_false"),
                        append(predicates, expected(predicate, false)),
                        baseline, compositeLayer, output, warnings, depth + 1);
                collect(object.get("on_true"),
                        append(predicates, expected(predicate, true)),
                        false, compositeLayer, output, warnings, depth + 1);
                return;
            }
            case "select" -> {
                String predicateProperty = matchProperty(property(object));
                if (predicateProperty == null) {
                    if (shortProperty(property(object))
                            .equals("display_context"))
                        warnings.add("Java display_context selector uses its "
                                + "fallback for held rendering; its GUI branch "
                                + "was retained as the Bedrock inventory icon");
                    else
                        warnings.add("Unsupported Java item select property '"
                                + property(object) + "' uses its fallback branch");
                    collect(fallbackOrFirst(object, "cases"), predicates, baseline,
                            compositeLayer, output, warnings, depth + 1);
                    return;
                }
                collect(object.get("fallback"), predicates, baseline,
                        compositeLayer, output, warnings, depth + 1);
                JsonArray cases = array(object.get("cases"));
                if (cases != null) for (JsonElement value : cases) {
                    if (!value.isJsonObject()) continue;
                    JsonObject entry = value.getAsJsonObject();
                    for (String when : strings(entry.get("when"))) {
                        JsonObject predicate = new JsonObject();
                        predicate.addProperty("type", "match");
                        predicate.addProperty("property", predicateProperty);
                        predicate.addProperty("value", when);
                        copyIndex(object, predicate);
                        collect(entry.get("model"), append(predicates, predicate),
                                false, compositeLayer, output, warnings, depth + 1);
                    }
                }
                return;
            }
            case "range_dispatch" -> {
                String predicateProperty = rangeProperty(property(object));
                if (predicateProperty == null) {
                    warnings.add("Unsupported Java item range property '"
                            + property(object) + "' uses its fallback branch");
                    collect(fallbackOrFirst(object, "entries"), predicates, baseline,
                            compositeLayer, output, warnings, depth + 1);
                    return;
                }
                collect(object.get("fallback"), predicates, baseline,
                        compositeLayer, output, warnings, depth + 1);
                JsonArray entries = array(object.get("entries"));
                if (entries != null) for (JsonElement value : entries) {
                    if (!value.isJsonObject()) continue;
                    JsonObject entry = value.getAsJsonObject();
                    if (!entry.has("threshold")
                            || !entry.get("threshold").isJsonPrimitive()) continue;
                    JsonObject predicate = new JsonObject();
                    predicate.addProperty("type", "range_dispatch");
                    predicate.addProperty("property", predicateProperty);
                    predicate.add("threshold", entry.get("threshold").deepCopy());
                    if (object.has("normalize"))
                        predicate.add("normalize", object.get("normalize").deepCopy());
                    if (object.has("scale"))
                        predicate.add("scale", object.get("scale").deepCopy());
                    copyIndex(object, predicate);
                    collect(entry.get("model"), append(predicates, predicate),
                            false, compositeLayer, output, warnings, depth + 1);
                }
                return;
            }
            case "composite" -> {
                JsonArray models = array(object.get("models"));
                if (models == null || models.isEmpty()) {
                    warnings.add("Composite Java item model has no visual layers");
                    return;
                }
                for (int index = 0; index < models.size(); index++)
                    collect(models.get(index), predicates,
                            baseline && index == 0,
                            true,
                            output, warnings, depth + 1);
                if (models.size() > 1)
                    warnings.add("Composite Java item model contains " + models.size()
                            + " visual layers; every layer was retained as a "
                            + "convertible visual asset");
                return;
            }
            case "special" -> {
                JsonElement base = object.get("base");
                String baseReference = primitive(base);
                if (baseReference != null) {
                    output.add(new Candidate(normalize(baseReference),
                            copy(predicates), baseline, compositeLayer));
                } else if (base != null) {
                    collect(base, predicates, baseline, compositeLayer,
                            output, warnings, depth + 1);
                } else {
                    warnings.add("Special Java item model has no convertible base model");
                }
                return;
            }
            default -> {
                for (String key : List.of("model", "models", "on_false", "fallback",
                        "on_true", "cases", "entries", "base")) {
                    JsonElement child = object.get(key);
                    if (child == null) continue;
                    if (child.isJsonPrimitive() && key.equals("model")) {
                        String value = primitive(child);
                        if (value != null)
                            output.add(new Candidate(normalize(value),
                                    copy(predicates), baseline, compositeLayer));
                        return;
                    }
                    int before = output.size();
                    collect(child, predicates, baseline, compositeLayer,
                            output, warnings, depth + 1);
                    if (output.size() > before) return;
                }
            }
        }
    }

    private JsonObject conditionPredicate(JsonObject node) {
        String property = conditionProperty(property(node));
        if (property == null) return null;
        JsonObject predicate = new JsonObject();
        predicate.addProperty("type", "condition");
        predicate.addProperty("property", property);
        if (property.equals("custom_model_data")) copyIndex(node, predicate);
        if (property.equals("has_component")) {
            String component = primitive(node.get("component"));
            if (component == null) return null;
            predicate.addProperty("component", normalizeIdentifier(component));
        }
        return predicate;
    }

    private String conditionProperty(String property) {
        return switch (shortProperty(property)) {
            case "broken" -> "broken";
            case "damaged" -> "damaged";
            case "custom_model_data" -> "custom_model_data";
            case "has_component" -> "has_component";
            case "fishing_rod/cast", "fishing_rod_cast" -> "fishing_rod_cast";
            default -> null;
        };
    }

    private String matchProperty(String property) {
        return switch (shortProperty(property)) {
            case "charge_type", "crossbow/charge_type" -> "charge_type";
            case "trim_material" -> "trim_material";
            case "context_dimension" -> "context_dimension";
            case "custom_model_data" -> "custom_model_data";
            default -> null;
        };
    }

    private String rangeProperty(String property) {
        return switch (shortProperty(property)) {
            case "bundle/fullness", "bundle_fullness" -> "bundle_fullness";
            case "damage" -> "damage";
            case "count" -> "count";
            case "custom_model_data" -> "custom_model_data";
            default -> null;
        };
    }

    private String property(JsonObject object) {
        String property = primitive(object.get("property"));
        return property == null ? "" : property;
    }

    private String shortProperty(String property) {
        if (property == null) return "";
        String value = property.toLowerCase(Locale.ROOT);
        int colon = value.indexOf(':');
        return colon < 0 ? value : value.substring(colon + 1);
    }

    private JsonElement fallbackOrFirst(JsonObject object, String entriesKey) {
        if (object.has("fallback")) return object.get("fallback");
        JsonArray entries = array(object.get(entriesKey));
        if (entries == null || entries.isEmpty()
                || !entries.get(0).isJsonObject()) return null;
        return entries.get(0).getAsJsonObject().get("model");
    }

    private JsonObject expected(JsonObject predicate, boolean value) {
        JsonObject copy = predicate.deepCopy();
        copy.addProperty("expected", value);
        return copy;
    }

    private List<JsonObject> append(List<JsonObject> predicates, JsonObject predicate) {
        List<JsonObject> result = copy(predicates);
        result.add(predicate.deepCopy());
        return List.copyOf(result);
    }

    private List<JsonObject> copy(List<JsonObject> predicates) {
        return new ArrayList<>(predicates.stream().map(JsonObject::deepCopy).toList());
    }

    private JsonArray array(JsonElement value) {
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private List<String> strings(JsonElement value) {
        if (value == null) return List.of();
        if (value.isJsonArray()) {
            List<String> result = new ArrayList<>();
            for (JsonElement child : value.getAsJsonArray())
                if (child.isJsonPrimitive()) result.add(child.getAsString());
            return result;
        }
        return value.isJsonPrimitive() ? List.of(value.getAsString()) : List.of();
    }

    private void copyIndex(JsonObject source, JsonObject target) {
        if (source.has("index") && source.get("index").isJsonPrimitive())
            target.add("index", source.get("index").deepCopy());
    }

    private String normalizeIdentifier(String value) {
        String clean = value.toLowerCase(Locale.ROOT).replace('\\', '/');
        return clean.contains(":") ? clean : "minecraft:" + clean;
    }

    private String normalize(String value) {
        String normalized = value.replace('\\', '/')
                .replaceFirst("(?i)\\.json$", "");
        if (normalized.startsWith("assets/")) {
            String asset = normalized.substring("assets/".length());
            int slash = asset.indexOf('/');
            if (slash > 0 && slash < asset.length() - 1)
                return asset.substring(0, slash) + ":"
                        + stripResourceRoot(asset.substring(slash + 1));
        }
        int colon = normalized.indexOf(':');
        if (colon >= 0)
            return normalized.substring(0, colon) + ":"
                    + stripResourceRoot(normalized.substring(colon + 1));
        return defaultNamespace + ":" + stripResourceRoot(normalized);
    }

    private String stripResourceRoot(String value) {
        return value.replaceFirst("^items/", "")
                .replaceFirst("^models/", "");
    }

    private String primitive(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }
}
