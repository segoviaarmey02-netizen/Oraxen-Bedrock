package dev.oraxenbedrock.conversion;

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
 * Resolves the model overrides used by Java resource packs before 1.21.4.
 *
 * <p>The custom-model-data predicate is evaluated while converting because a
 * Geyser legacy definition already matches one exact custom model data value.
 * Runtime predicates which have a Geyser v2 equivalent are retained on
 * additional visual definitions.</p>
 */
final class LegacyJavaItemModelResolver {
    record Result(String model, boolean modelFound,
                  List<JavaItemModelResolver.Variant> variants,
                  List<String> warnings) {}

    private record ConvertedPredicates(
            boolean matches, List<List<JsonObject>> alternatives) {}

    private final PackSource pack;
    private final String defaultNamespace;

    LegacyJavaItemModelResolver(PackSource pack, String defaultNamespace) {
        this.pack = pack;
        this.defaultNamespace = defaultNamespace;
    }

    Result resolve(String reference, Integer customModelData) throws IOException {
        if (reference == null || reference.isBlank())
            return new Result(null, false, List.of(), List.of());

        String current = normalize(reference);
        Path initial = modelPath(current);
        if (initial == null || !Files.isRegularFile(initial))
            return new Result(current, false, List.of(), List.of());

        List<String> warnings = new ArrayList<>();
        var variantsByPredicate =
                new java.util.LinkedHashMap<String,
                        JavaItemModelResolver.Variant>();
        Set<String> visited = new LinkedHashSet<>();
        boolean found = false;

        /*
         * A static matching override becomes the new baseline. Following that
         * baseline also finds state overrides stored in the selected custom
         * model (the common bow/crossbow layout).
         */
        for (int depth = 0; depth < 32 && visited.add(current); depth++) {
            JsonObject model = resolvedModel(current, new LinkedHashSet<>(), 0);
            if (model == null) break;
            found = true;
            if (!model.has("overrides") || !model.get("overrides").isJsonArray())
                break;

            String nextBaseline = null;
            List<JavaItemModelResolver.Variant> levelVariants = new ArrayList<>();
            var overrides = model.getAsJsonArray("overrides");
            for (int index = 0; index < overrides.size(); index++) {
                JsonElement value = overrides.get(index);
                if (!value.isJsonObject()) continue;
                JsonObject override = value.getAsJsonObject();
                String target = string(override.get("model"));
                JsonObject predicate = override.has("predicate")
                        && override.get("predicate").isJsonObject()
                        ? override.getAsJsonObject("predicate") : new JsonObject();
                if (target == null || target.isBlank()) continue;

                ConvertedPredicates converted = convertPredicates(
                        predicate, customModelData, current, warnings);
                if (!converted.matches()) continue;
                String normalizedTarget = normalize(target);
                if (converted.alternatives().isEmpty()) {
                    // All conditions are fixed/no-op for this item.
                    nextBaseline = normalizedTarget;
                    // Java uses the last matching override. A later
                    // unconditional match makes all earlier state matches in
                    // this model unreachable.
                    levelVariants.clear();
                    continue;
                }
                for (List<JsonObject> alternative : converted.alternatives())
                    levelVariants.add(new JavaItemModelResolver.Variant(
                            normalizedTarget, copy(alternative)));
            }
            /*
             * Java uses the last matching override within one model, but an
             * override selected in this (outer) model prevents state
             * overrides from the baseline child model from being reached.
             * Deduplicate each level last-wins, then retain the first level
             * that defines a given predicate.
             */
            var levelByPredicate =
                    new java.util.LinkedHashMap<String,
                            JavaItemModelResolver.Variant>();
            for (JavaItemModelResolver.Variant variant : levelVariants)
                levelByPredicate.put(predicateKey(variant.predicates()), variant);
            levelByPredicate.forEach(variantsByPredicate::putIfAbsent);
            if (nextBaseline == null || nextBaseline.equals(current)) break;
            current = nextBaseline;
        }
        if (visited.size() >= 32)
            warnings.add("Legacy item model override chain exceeded 32 models");

        /*
         * Equal predicates select only the last matching Java override. This
         * also turns the standard crossbow pair (charged, then firework) into
         * an arrow mapping plus the later rocket mapping without ambiguity.
         */
        List<JavaItemModelResolver.Variant> variants = new ArrayList<>();
        for (JavaItemModelResolver.Variant variant
                : variantsByPredicate.values())
            if (!variant.model().equals(current)
                    || !variant.predicates().isEmpty())
                variants.add(variant);
        return new Result(current, found, List.copyOf(variants),
                List.copyOf(new LinkedHashSet<>(warnings)));
    }

    private ConvertedPredicates convertPredicates(
            JsonObject source, Integer customModelData,
            String owner, List<String> warnings) {
        List<List<JsonObject>> alternatives = new ArrayList<>();
        alternatives.add(new ArrayList<>());
        boolean hasDynamicPredicate = false;
        boolean charged = positive(source, "charged");
        boolean firework = positive(source, "firework");

        for (var entry : source.entrySet()) {
            String property = shortProperty(entry.getKey());
            Double threshold = number(entry.getValue());
            if (threshold == null) {
                warnings.add("Legacy override in '" + owner
                        + "' has a non-numeric predicate '" + entry.getKey() + "'");
                return new ConvertedPredicates(false, List.of());
            }
            switch (property) {
                case "custom_model_data" -> {
                    if (customModelData == null) {
                        JsonObject predicate = range(
                                "custom_model_data", threshold, false);
                        predicate.addProperty("index", 0);
                        appendAll(alternatives, predicate);
                        hasDynamicPredicate = true;
                    } else if (customModelData.doubleValue() < threshold) {
                        return new ConvertedPredicates(false, List.of());
                    }
                }
                case "damage" -> {
                    if (threshold > 1) return new ConvertedPredicates(false, List.of());
                    if (threshold > 0) {
                        appendAll(alternatives, range("damage", threshold, true));
                        hasDynamicPredicate = true;
                    }
                }
                case "damaged" -> {
                    if (threshold > 1) return new ConvertedPredicates(false, List.of());
                    if (threshold > 0) {
                        appendAll(alternatives, condition("damaged"));
                        hasDynamicPredicate = true;
                    }
                }
                case "cast" -> {
                    if (threshold > 1) return new ConvertedPredicates(false, List.of());
                    if (threshold > 0) {
                        appendAll(alternatives, condition("fishing_rod_cast"));
                        hasDynamicPredicate = true;
                    }
                }
                case "charged", "firework" -> {
                    if (threshold > 1) return new ConvertedPredicates(false, List.of());
                    // Converted together below so charged can expand to both
                    // valid Geyser charge types.
                }
                case "pulling", "pull", "blocking" -> {
                    if (threshold > 1)
                        return new ConvertedPredicates(false, List.of());
                    if (threshold > 0) {
                        warnings.add("Legacy Java predicate '" + property
                                + "' in '" + owner + "' has no Geyser v2 item "
                                + "predicate and cannot select a separate Bedrock item");
                        return new ConvertedPredicates(false, List.of());
                    }
                }
                default -> {
                    if (threshold > 0) {
                        warnings.add("Unsupported legacy Java item predicate '"
                                + entry.getKey() + "' in '" + owner + "'");
                        return new ConvertedPredicates(false, List.of());
                    }
                }
            }
        }

        if (firework) {
            appendAll(alternatives, match("charge_type", "rocket"));
            hasDynamicPredicate = true;
        } else if (charged) {
            List<List<JsonObject>> expanded = new ArrayList<>();
            for (List<JsonObject> predicates : alternatives) {
                List<JsonObject> arrow = copy(predicates);
                arrow.add(match("charge_type", "arrow"));
                expanded.add(arrow);
                List<JsonObject> rocket = copy(predicates);
                rocket.add(match("charge_type", "rocket"));
                expanded.add(rocket);
            }
            alternatives = expanded;
            hasDynamicPredicate = true;
        }

        return new ConvertedPredicates(true,
                hasDynamicPredicate ? alternatives : List.of());
    }

    /**
     * Resolves inherited overrides. Java child models inherit the parent's
     * overrides only when they do not declare their own override array.
     */
    private JsonObject resolvedModel(
            String reference, Set<String> visited, int depth) throws IOException {
        if (depth > 32 || !visited.add(reference)) return null;
        Path path = modelPath(reference);
        if (path == null || !Files.isRegularFile(path)) return null;
        JsonObject child = JsonSupport.readObject(path);
        if (child.has("overrides")) return child;
        String parent = string(child.get("parent"));
        if (parent == null) return child;
        JsonObject inherited = resolvedModel(normalize(parent), visited, depth + 1);
        if (inherited != null && inherited.has("overrides"))
            child.add("overrides", inherited.get("overrides").deepCopy());
        return child;
    }

    private Path modelPath(String reference) {
        int colon = reference.indexOf(':');
        if (colon < 1 || colon == reference.length() - 1) return null;
        return pack.findAsset(reference.substring(0, colon),
                "models/" + reference.substring(colon + 1) + ".json");
    }

    private JsonObject condition(String property) {
        JsonObject predicate = new JsonObject();
        predicate.addProperty("type", "condition");
        predicate.addProperty("property", property);
        return predicate;
    }

    private JsonObject match(String property, String value) {
        JsonObject predicate = new JsonObject();
        predicate.addProperty("type", "match");
        predicate.addProperty("property", property);
        predicate.addProperty("value", value);
        return predicate;
    }

    private JsonObject range(String property, double threshold, boolean normalize) {
        JsonObject predicate = new JsonObject();
        predicate.addProperty("type", "range_dispatch");
        predicate.addProperty("property", property);
        predicate.addProperty("threshold", threshold);
        if (normalize) predicate.addProperty("normalize", true);
        return predicate;
    }

    private void appendAll(List<List<JsonObject>> alternatives, JsonObject predicate) {
        alternatives.forEach(value -> value.add(predicate.deepCopy()));
    }

    private List<JsonObject> copy(List<JsonObject> predicates) {
        return new ArrayList<>(
                predicates.stream().map(JsonObject::deepCopy).toList());
    }

    private String predicateKey(List<JsonObject> predicates) {
        return predicates.stream()
                .map(JsonSupport.GSON::toJson)
                .sorted()
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private boolean positive(JsonObject source, String property) {
        for (var entry : source.entrySet())
            if (shortProperty(entry.getKey()).equals(property)) {
                Double value = number(entry.getValue());
                return value != null && value > 0 && value <= 1;
            }
        return false;
    }

    private Double number(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) return null;
        double number = value.getAsDouble();
        return Double.isFinite(number) ? number : null;
    }

    private String shortProperty(String property) {
        String value = property.toLowerCase(Locale.ROOT);
        int colon = value.indexOf(':');
        return colon < 0 ? value : value.substring(colon + 1);
    }

    private String normalize(String value) {
        String normalized = value.trim().replace('\\', '/')
                .replaceFirst("(?i)\\.json$", "");
        if (normalized.startsWith("assets/")) {
            String asset = normalized.substring("assets/".length());
            int slash = asset.indexOf('/');
            if (slash > 0) {
                String namespace = asset.substring(0, slash);
                String path = asset.substring(slash + 1)
                        .replaceFirst("^models/", "");
                return namespace + ":" + path;
            }
        }
        normalized = normalized.replaceFirst("^models/", "");
        int colon = normalized.indexOf(':');
        if (colon >= 0) {
            String namespace = normalized.substring(0, colon);
            String path = normalized.substring(colon + 1)
                    .replaceFirst("^models/", "");
            return namespace + ":" + path;
        }
        return defaultNamespace + ":" + normalized;
    }

    private String string(JsonElement value) {
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }
}
