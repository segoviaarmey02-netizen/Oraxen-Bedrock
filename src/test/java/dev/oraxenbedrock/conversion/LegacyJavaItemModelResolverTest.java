package dev.oraxenbedrock.conversion;

import com.google.gson.JsonObject;
import dev.oraxenbedrock.io.PackSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LegacyJavaItemModelResolverTest {
    @TempDir
    Path temp;

    @Test
    void resolvesLegacyOverridesIntoGeyserV2Predicates() throws Exception {
        writeModel("item/tool", """
                {
                  "parent": "minecraft:item/handheld",
                  "overrides": [
                    {"predicate":{"custom_model_data":10},
                     "model":"oraxen:item/tool_base"},
                    {"predicate":{"custom_model_data":10,"damaged":1,"damage":0.5},
                     "model":"oraxen:item/tool_damaged"},
                    {"predicate":{"custom_model_data":10,"cast":1},
                     "model":"oraxen:item/tool_cast"},
                    {"predicate":{"custom_model_data":10,"charged":1},
                     "model":"oraxen:item/tool_charged"},
                    {"predicate":{"custom_model_data":10,"charged":1,"firework":1},
                     "model":"oraxen:item/tool_rocket"},
                    {"predicate":{"custom_model_data":20},
                     "model":"oraxen:item/wrong_cmd"},
                    {"predicate":{"custom_model_data":10,"pulling":1},
                     "model":"oraxen:item/tool_pulling"},
                    {"predicate":{"custom_model_data":10,"blocking":1},
                     "model":"oraxen:item/tool_blocking"}
                  ]
                }
                """);
        writeModel("item/tool_base", """
                {"parent":"minecraft:item/handheld"}
                """);

        try (PackSource source = PackSource.open(temp)) {
            LegacyJavaItemModelResolver.Result result =
                    new LegacyJavaItemModelResolver(source, "oraxen")
                            .resolve("item/tool", 10);

            assertTrue(result.modelFound());
            assertEquals("oraxen:item/tool_base", result.model());
            assertEquals(4, result.variants().size());
            assertFalse(result.variants().stream()
                    .anyMatch(value -> value.model().endsWith("wrong_cmd")));
            assertFalse(result.variants().stream()
                    .anyMatch(value -> value.model().endsWith("tool_pulling")));
            assertFalse(result.variants().stream()
                    .anyMatch(value -> value.model().endsWith("tool_blocking")));

            JavaItemModelResolver.Variant damaged =
                    variant(result.variants(), "oraxen:item/tool_damaged");
            assertEquals(2, damaged.predicates().size());
            assertPredicate(damaged.predicates().get(0),
                    "condition", "damaged");
            JsonObject damage = damaged.predicates().get(1);
            assertPredicate(damage, "range_dispatch", "damage");
            assertEquals(0.5, damage.get("threshold").getAsDouble());
            assertTrue(damage.get("normalize").getAsBoolean());

            assertPredicate(variant(result.variants(), "oraxen:item/tool_cast")
                            .predicates().get(0),
                    "condition", "fishing_rod_cast");

            List<JavaItemModelResolver.Variant> charged =
                    result.variants().stream().filter(value ->
                            value.model().equals("oraxen:item/tool_charged")).toList();
            assertEquals(1, charged.size());
            assertEquals(List.of("arrow"), charged.stream()
                    .map(value -> value.predicates().get(0).get("value").getAsString())
                    .toList());

            JsonObject rocket = variant(result.variants(), "oraxen:item/tool_rocket")
                    .predicates().get(0);
            assertPredicate(rocket, "match", "charge_type");
            assertEquals("rocket", rocket.get("value").getAsString());
            assertTrue(result.warnings().stream()
                    .anyMatch(value -> value.contains("'pulling'")));
            assertTrue(result.warnings().stream()
                    .anyMatch(value -> value.contains("'blocking'")));
        }
    }

    @Test
    void inheritsOverridesAndKeepsUnknownCustomModelDataDynamic() throws Exception {
        writeModel("item/parent", """
                {"overrides":[
                  {"predicate":{"custom_model_data":3},
                   "model":"oraxen:item/three"}
                ]}
                """);
        writeModel("item/child", """
                {"parent":"oraxen:item/parent"}
                """);

        try (PackSource source = PackSource.open(temp)) {
            LegacyJavaItemModelResolver.Result inherited =
                    new LegacyJavaItemModelResolver(source, "oraxen")
                            .resolve("oraxen:item/child", 3);
            assertEquals("oraxen:item/three", inherited.model());
            assertTrue(inherited.variants().isEmpty());

            LegacyJavaItemModelResolver.Result dynamic =
                    new LegacyJavaItemModelResolver(source, "oraxen")
                            .resolve("oraxen:item/child", null);
            assertEquals("oraxen:item/child", dynamic.model());
            JsonObject predicate = variant(
                    dynamic.variants(), "oraxen:item/three").predicates().get(0);
            assertPredicate(predicate, "range_dispatch", "custom_model_data");
            assertEquals(3, predicate.get("threshold").getAsDouble());
            assertEquals(0, predicate.get("index").getAsInt());
        }
    }

    @Test
    void outerStateOverrideWinsOverBaselineChildOverride() throws Exception {
        writeModel("item/root", """
                {"overrides":[
                  {"predicate":{"custom_model_data":10},
                   "model":"oraxen:item/selected"},
                  {"predicate":{"custom_model_data":10,"damaged":1},
                   "model":"oraxen:item/outer_damaged"}
                ]}
                """);
        writeModel("item/selected", """
                {"overrides":[
                  {"predicate":{"damaged":1},
                   "model":"oraxen:item/child_damaged"}
                ]}
                """);

        try (PackSource source = PackSource.open(temp)) {
            LegacyJavaItemModelResolver.Result result =
                    new LegacyJavaItemModelResolver(source, "oraxen")
                            .resolve("oraxen:item/root", 10);

            assertEquals("oraxen:item/selected", result.model());
            assertEquals(1, result.variants().size());
            assertEquals("oraxen:item/outer_damaged",
                    result.variants().get(0).model(),
                    "Selecting an override in the outer model prevents the "
                            + "baseline child's override from being evaluated");
            assertPredicate(result.variants().get(0).predicates().get(0),
                    "condition", "damaged");
        }
    }

    @Test
    void deduplicatesEquivalentPredicatesRegardlessOfSourceKeyOrder()
            throws Exception {
        writeModel("item/ordered", """
                {"overrides":[
                  {"predicate":{"custom_model_data":10},
                   "model":"oraxen:item/base"},
                  {"predicate":{"custom_model_data":10,"damaged":1,"damage":0.5},
                   "model":"oraxen:item/first"},
                  {"predicate":{"damage":0.5,"damaged":1,"custom_model_data":10},
                   "model":"oraxen:item/last"},
                  {"predicate":{"custom_model_data":10,"damage":1.1},
                   "model":"oraxen:item/impossible"}
                ]}
                """);

        try (PackSource source = PackSource.open(temp)) {
            LegacyJavaItemModelResolver.Result result =
                    new LegacyJavaItemModelResolver(source, "oraxen")
                            .resolve("oraxen:item/ordered", 10);

            assertEquals("oraxen:item/base", result.model());
            assertEquals(1, result.variants().size());
            assertEquals("oraxen:item/last", result.variants().get(0).model(),
                    "Java's last matching override must win even when JSON key "
                            + "order differs");
            assertEquals(2, result.variants().get(0).predicates().size());
            assertFalse(result.variants().stream()
                    .anyMatch(variant -> variant.model().endsWith("impossible")));
        }
    }

    private JavaItemModelResolver.Variant variant(
            List<JavaItemModelResolver.Variant> variants, String model) {
        return variants.stream().filter(value -> value.model().equals(model))
                .findFirst().orElseThrow();
    }

    private void assertPredicate(
            JsonObject predicate, String type, String property) {
        assertEquals(type, predicate.get("type").getAsString());
        assertEquals(property, predicate.get("property").getAsString());
    }

    private void writeModel(String model, String json) throws Exception {
        Path file = temp.resolve("assets/oraxen/models")
                .resolve(model + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }
}
