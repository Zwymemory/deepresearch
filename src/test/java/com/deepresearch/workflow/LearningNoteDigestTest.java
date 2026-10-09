package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.file.*;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.*;

class LearningNoteDigestTest {
    LearningNoteDigest.Source source(String id,String answer){return new LearningNoteDigest.Source(id,"构造器注入",answer);}
    @Test void realTutorialSampleRetainsDefinitionsExerciseAndPrerequisitesWithFewerRepeats() throws Exception {
        var fixture=JSON.readTree(getClass().getResourceAsStream("/learning-notes/tutorial-excerpts.json"));
        var sources=new ArrayList<LearningNoteDigest.Source>();
        fixture.path("sources").forEach(s->sources.add(new LearningNoteDigest.Source(s.path("run_id").asText(),s.path("question").asText(),s.path("answer").asText())));
        var digest=LearningNoteDigest.create(sources,8);
        Files.writeString(Path.of("target/learning-digest-preview.json"),canonical(object("fixture_only",true,"sources",fixture.path("sources"),"digest",digest)));
        assertThat(digest.path("stats").path("merged_count").asInt()).isGreaterThanOrEqualTo(3);
        String visible=String.join("\n",digest.path("points").findValuesAsText("text"));
        assertThat(visible).contains("Spring Bean", "构造器", "PaymentGateway", "题目", "@Autowired");
        assertThat(visible).doesNotContain("适用版本：未确定", "范围说明", "以上代码为说明性示例");
        exactSources(sources,digest);
        var bounded=LearningNoteDigest.create(sources,8,1000);
        assertThat(String.join("",bounded.path("points").findValuesAsText("text")).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1000);
        exactSources(sources,bounded);
    }
    @Test void mergesSimilarRepetitionsWhileKeepingOriginalSpansAndDifferentKnowledge() {
        var sources=List.of(
                source("new","😀Spring 容器负责创建已经注册的 PaymentGateway Bean，并通过构造器把它传给 OrderService。\n如果一个 Bean 有多个构造器，需要用 @Autowired 标注想使用的构造器。"),
                source("old","😀Spring 容器负责创建已注册的 PaymentGateway Bean，并通过构造器把它传给 OrderService。\nSpring Bean 就是由 Spring 容器注册和管理的应用组件对象。\n题目：请编写一个 OrderService 类，并通过构造器接收 PaymentGateway。\n```java\nclass SecretImplementation {}\n```"));
        var digest=LearningNoteDigest.create(sources,8);
        assertThat(digest.path("stats").path("merged_count").asInt()).isEqualTo(1);
        assertThat(digest.path("points")).hasSize(4);
        var merged=new ArrayList<JsonNode>();digest.path("points").forEach(p->{if(p.path("occurrence_count").asInt()>1)merged.add(p);});
        assertThat(merged).hasSize(1);assertThat(merged.get(0).path("sources")).hasSize(2);
        assertThat(digest.toString()).doesNotContain("SecretImplementation");
        assertThat(digest.path("points").findValuesAsText("category")).contains("concept","condition","practice");
        exactSources(sources,digest);
    }
    @Test void preservesNegationNumbersVersionsIdentifiersAndConditionalChanges() {
        var sources=List.of(
                source("v1","Spring Boot 2.7 的例子中，PaymentGateway 需要注册为 Bean 才能交给容器管理，并通过构造器装配依赖。"),
                source("v2","Spring Boot 3.0 的例子中，PaymentGateway 需要注册为 Bean 才能交给容器管理，并通过构造器装配依赖。"),
                source("positive","在这个构造器注入例子中，PaymentGateway 可以由容器自动装配并传给已经注册的 OrderService。"),
                source("negative","在这个构造器注入例子中，PaymentGateway 不可以由容器自动装配并传给已经注册的 OrderService。"),
                source("one","如果只有一个构造器，OrderService 可以直接通过构造器接收已经注册的 PaymentGateway Bean 并保存依赖。"),
                source("two","如果有两个构造器，OrderService 可以直接通过构造器接收已经注册的 PaymentGateway Bean 并保存依赖。"),
                source("identifier","在这个构造器注入例子中，OtherGateway 可以由容器自动装配并传给已经注册的 OrderService。"),
                source("operator","条件 timeout >= 10 时，测试任务可以读取已经保存的 PaymentGateway 注册记录并恢复当前研究步骤。"),
                source("reverse","条件 timeout <= 10 时，测试任务可以读取已经保存的 PaymentGateway 注册记录并恢复当前研究步骤。"));
        var digest=LearningNoteDigest.create(sources,20);
        assertThat(digest.path("stats").path("unique_count").asInt()).isEqualTo(sources.size());
        assertThat(digest.path("stats").path("merged_count").asInt()).isZero();
        exactSources(sources,digest);
    }
    @Test void equivalentConditionalWordingMergesButDifferentConditionsDoNot() {
        var sources=List.of(
                source("a","官方文档还指出，若一个 Bean 有多个构造器，需用 @Autowired 标注希望 Spring 使用的那一个。"),
                source("b","文档还指出，如果一个 Bean 有多个构造器，需要用 @Autowired 标注希望 Spring 使用的那一个。"),
                source("c","文档还指出，如果一个 Bean 只有一个构造器，需要用 @Autowired 标注希望 Spring 使用的那一个。"));
        var digest=LearningNoteDigest.create(sources,8);
        assertThat(digest.path("stats").path("merged_count").asInt()).isEqualTo(1);
        assertThat(digest.path("points")).hasSize(2);
        exactSources(sources,digest);
    }
    @Test void identicalSentenceFromDifferentVersionedQuestionsDoesNotBecomeOneUnscopedPoint() {
        String text="构造器通过参数声明依赖，并在创建这个对象时接收容器传入的依赖实例。";
        var sources=List.of(new LearningNoteDigest.Source("v2","Spring Boot 2.7 的构造器",text),
                new LearningNoteDigest.Source("v3","Spring Boot 3.0 的构造器",text));
        var digest=LearningNoteDigest.create(sources,8);
        assertThat(digest.path("points")).hasSize(2);
        assertThat(new HashSet<>(digest.path("points").findValuesAsText("point_id"))).hasSize(2);
    }
    @Test void environmentAndEnglishConditionsDoNotMergeThroughSharedLongWording() {
        String explanation="，可以读取已经保存的研究进度和来源引用，并在确认项目权限之后恢复尚未完成的研究步骤，避免重复创建已经执行完成的研究任务。";
        var sources=List.of(source("prod","在生产环境下"+explanation),source("dev","在开发环境下"+explanation),
                source("enabled","If caching is enabled, the application retrieves the saved research progress and references before continuing the remaining research steps for the current project."),
                source("disabled","If caching is disabled, the application retrieves the saved research progress and references before continuing the remaining research steps for the current project."));
        var digest=LearningNoteDigest.create(sources,8);
        assertThat(digest.path("stats").path("merged_count").asInt()).isZero();
        assertThat(digest.path("stats").path("unique_count").asInt()).isEqualTo(4);
        exactSources(sources,digest);
    }
    @Test void presentationMetadataDoesNotHideEtagsButMaterialScopeIsNeverDetached() {
        String fact="HTTP 响应头 ETag 是某个资源特定版本的标识符。";
        String suffix="（适用版本：未确定，仅描述引用快照；有效时间未确定；条件：[\"HTTP 的 ETag 用于什么？\",\"依据 MDN 官方文档回答\"]） [来源1]";
        var source=new LearningNoteDigest.Source("http","HTTP 的 ETag 用于什么？请根据 MDN 官方文档回答。",fact+suffix+"\n依据 MDN 官方文档（MDN Web Docs）回答，而非其他来源。");
        var digest=LearningNoteDigest.create(List.of(source),8);
        assertThat(digest.path("points")).hasSize(1);
        assertThat(digest.path("points").get(0).path("text").asText()).isEqualTo(fact);
        String scoped=fact+suffix.replace("依据 MDN 官方文档回答","仅限 Java 17 环境");
        var meaningful=LearningNoteDigest.create(List.of(new LearningNoteDigest.Source("scoped",source.question(),scoped)),8);
        assertThat(meaningful.path("points").get(0).path("text").asText()).isEqualTo(scoped);
    }
    @Test void retainsAntecedentsAndAttachedScopeInsteadOfTurningFragmentsIntoFacts() {
        String conditional="只有 PaymentGateway 先注册成 Bean 才能被容器找到。否则它不会自动注入 OrderService 中。";
        String scoped="这个方法可以用于配置组件扫描并发现项目中的服务。（适用版本：Spring Boot 3.0；条件：仅限此示例）";
        var sources=List.of(source("conditional",conditional+"\n"+scoped));
        var digest=LearningNoteDigest.create(sources,8);
        assertThat(digest.path("points").findValuesAsText("text")).contains(conditional,scoped);
        exactSources(sources,digest);
    }
    @Test void omitsLongIncompleteSentencesAndDanglingCodeIntroductionsWithoutDeletingOriginals() {
        String answer="只有"+"很长的适用条件".repeat(80)+"满足时，才允许使用这个结论。\n最小代码示例：\n```java\nclass Example {}\n```\n构造器用于声明创建对象时需要提供的依赖。";
        var sources=List.of(source("long",answer));
        var digest=LearningNoteDigest.create(sources,8);
        assertThat(digest.path("points")).hasSize(1);
        assertThat(digest.path("points").get(0).path("text").asText()).isEqualTo("构造器用于声明创建对象时需要提供的依赖。");
        assertThat(sources.get(0).answer()).isEqualTo(answer);
    }
    private void exactSources(List<LearningNoteDigest.Source> sources,JsonNode digest) {
        var originals=new HashMap<String,String>();sources.forEach(s->originals.put(s.runId(),s.answer()));
        for(var point:digest.path("points")) {
            var ref=point.path("representative");
            assertThat(originals.get(ref.path("run_id").asText()).substring(ref.path("start").asInt(),ref.path("end").asInt())).isEqualTo(point.path("text").asText());
            for(var other:point.path("sources"))assertThat(originals.get(other.path("run_id").asText()).substring(other.path("start").asInt(),other.path("end").asInt())).isNotBlank();
        }
    }
}
