package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;
import static com.deepresearch.evidence.EvidenceJson.*;

/** Extractive notes: every discussion excerpt is an exact span of the original answer. */
final class LearningNoteText {
    private LearningNoteText() {}
    static String shortText(String value,int limit) {
        return value.codePointCount(0,value.length())<=limit?value:value.substring(0,value.offsetByCodePoints(0,limit))+"…";
    }
    static boolean followup(String question) {
        return question.matches("(?s).*(这道题|那道题|第二问|第[一二三四五六七八九十0-9]+[题问]|这段代码|接着|继续上次|没理解|没明白|还不懂|不太懂).*")
            || (question.length()<55 && question.matches("(?s)^(那|这个|这里|所以|为什么|能再|再解释|请继续|继续).*"));
    }
    static boolean exercise(String text) { return text.matches("(?s).*(练习|题目|小问|习题|exercise).*" ); }
    static Set<String> terms(String text) {
        String clean=text.toLowerCase(Locale.ROOT).replaceAll("https?://\\S+", " ");
        // Remove instruction boilerplate before Chinese bigrams are formed, including cross-word pairs.
        clean=clean.replaceAll("请根据|根据|官方文档|官方网页|官方|文档|网页|用中文|中文|一小段|一段|请|解释|说明|回答|问题|练习题|练习|举例|只回答|不出|先换个话题|换个话题|继续|上次|这道题|那道题|答案|用途", " ");
        var result=new LinkedHashSet<String>();
        var ascii=Pattern.compile("[a-z][a-z0-9_]{2,}").matcher(clean);
        var stop=Set.of("the","and","for","this","that","why","please","java","service","public","class","void","string");
        while(ascii.find()) if(!stop.contains(ascii.group())) result.add(ascii.group());
        var chinese=Pattern.compile("[\\p{IsHan}]{2,}").matcher(clean);
        var common=Set.of("请根","根据","官方","文档","中文","解释","一下","为什么","为什","什么","一个","这道","道题","答案","继续","上次","问题","学习","如何","然后","以及","举例","练习","题目");
        while(chinese.find()) {String s=chinese.group();for(int i=0;i<s.length()-1;i++){String part=s.substring(i,i+2);if(!common.contains(part))result.add(part);}}
        return result;
    }
    static int score(String question,String material) {
        var desired=terms(question);var known=terms(material);int score=0;
        for(var term:desired) if(known.contains(term)) score+=term.matches("[a-z].*")?4:1;
        return score;
    }
    static String title(String question) {
        String result=question.replaceFirst("^请根据.{0,80}?官方文档[，,]?","")
            .replaceFirst("^(请|用中文|帮我|介绍一下|解释一下|解释)+", "").strip();
        result=result.split("[。！？\\n]",2)[0];
        return shortText(result.isBlank()?question:result,60);
    }
    static JsonNode extract(String question,String answer,JsonNode progress) {
        var spans=JSON.createArrayNode();boolean code=false;int offset=0;
        for(String line:answer.split("\n",-1)) {
            String text=line.strip();
            if(text.startsWith("```")) code=!code;
            else if(!code && text.length()>=12 && !text.startsWith("#") && !text.startsWith("|")
                    && !text.matches("^(来源|参考资料|证据|范围说明|说明：上述|上一轮|研究报告|Source|https?://).*")) {
                int start=offset+line.indexOf(text);
                // Keep whole sentences; never cut a qualifier into an apparent factual claim.
                if(text.codePointCount(0,text.length())<=360 && spans.size()<4)
                    spans.add(object("text",text,"start",start,"end",start+text.length()));
                else if(spans.size()<4) {
                    int from=0;
                    for(int i=0;i<text.length();i++) if("。！？".indexOf(text.charAt(i))>=0) {
                        String sentence=text.substring(from,i+1);
                        if(sentence.codePointCount(0,sentence.length())>=12 && sentence.codePointCount(0,sentence.length())<=360 && spans.size()<4)
                            spans.add(object("text",sentence,"start",start+from,"end",start+i+1));
                        from=i+1;
                    }
                }
            }
            offset+=line.length()+1;
        }
        var pending=JSON.createArrayNode();
        if(question.matches("(?s).*(没理解|没明白|不太懂|还不懂|没有理解|还没搞懂).*"))
            pending.add(object("text",question,"kind","user_question"));
        if("INSUFFICIENT_EVIDENCE".equals(progress.path("learning_run_status").asText()))
            pending.add(object("text",shortText(question,300),"kind","research_gap"));
        return object("discussed",spans,"open_questions",pending,"has_exercise",exercise(answer),
                "has_code",answer.contains("```"),"user_correction",progress.path("user_correction").asText(""));
    }
}
