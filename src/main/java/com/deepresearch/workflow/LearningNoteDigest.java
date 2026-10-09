package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;
import static com.deepresearch.evidence.EvidenceJson.*;

/** Bounded source selection, not generated prose. Offsets always address the unchanged original. */
final class LearningNoteDigest {
    static final String METHOD="source-grouped-excerpts/1";
    private static final int MAX_PER_SOURCE=32, MAX_TEXT=360;
    private static final List<String> CATEGORIES=List.of("concept","condition","practice");
    private static final Pattern CRITICAL=Pattern.compile(
            "-?\\d+(?:[.:-]\\d+)*(?:%|ms|秒|分钟|天|次|个)?|[<>]=?|!=|==|[-+*/%]|@[A-Za-z_$][\\w$]*|"
            +"[A-Z][a-z]+(?:[A-Z][A-Za-z0-9]*)+|[A-Z]{2,}[A-Za-z0-9]*|[A-Za-z]+_[A-Za-z_]+|"
            +"[\\\"'`][^\\\"'`\\n]{1,80}[\\\"'`]|"
            +"不需要|无需|不能|不可|不得|不应|不会|不是|不支持|没有|未|无|不|必须|需要|推荐|建议|可以|应当|"
            +"单个|多个|只有一个|一个|两个|全部|所有|部分|至少|至多|最多|最少|大于|小于|等于|"
            +"(?i:\\b(?:not|never|must|should|may|can|cannot|without|only|except|strong|weak)\\b)");
    private static final Pattern CONDITIONS=Pattern.compile("(?:如果|只要|只有|除非|若|当|在|对于|针对|前提是|条件是)[^，,。；;！？\\n]{1,100}"
            +"|(?i:\\b(?:if|unless|when|provided that|under)\\b)[^,.;!?\\n]{1,150}");

    record Source(String runId,String question,String answer) {}
    private record Span(Source source,int start,int end,String category,String text,String normalized,
                        String guard,Set<String> grams,int order) {
        JsonNode ref(){return object("run_id",source.runId(),"start",start,"end",end);}
    }
    private static final class Group {
        final Span representative;
        final List<Span> spans=new ArrayList<>();
        Group(Span span){representative=span;spans.add(span);}
        JsonNode json(){return object("point_id","point-"+sha(representative.category()+":"+representative.guard()+":"+representative.text()).substring(0,16),
                "category",representative.category(),"text",representative.text(),"representative",representative.ref(),
                "sources",spans.stream().map(Span::ref).toList(),"occurrence_count",spans.size());}
    }

    static JsonNode create(List<Source> sources,int limit) {
        return create(sources,limit,Integer.MAX_VALUE);
    }
    static JsonNode create(List<Source> sources,int limit,int textByteBudget) {
        var candidates=new ArrayList<Span>();
        for(var source:sources) candidates.addAll(extract(source,candidates.size()));
        var groups=new ArrayList<Group>();
        var buckets=new HashMap<String,List<Group>>();
        for(var span:candidates) {
            var bucket=buckets.computeIfAbsent(span.category()+"\n"+span.guard(),ignored->new ArrayList<>());
            Group match=null;
            for(var group:bucket) if(duplicates(span,group.representative)){match=group;break;}
            if(match==null){match=new Group(span);bucket.add(match);groups.add(match);}else match.spans.add(span);
        }
        var selected=new ArrayList<Group>();
        int remaining=textByteBudget;
        // Select different kinds of knowledge, so repeated recent solutions do not displace definitions or the exercise.
        for(String category:CATEGORIES) {
            var pool=new ArrayList<>(groups.stream().filter(g->g.representative.category().equals(category)).toList());
            int quota=category.equals("practice")?2:3;
            var section=new ArrayList<Group>();
            while(!pool.isEmpty() && section.size()<quota && selected.size()<limit) {
                Group best=null;double bestScore=Double.NEGATIVE_INFINITY;
                for(var candidate:pool) {
                    if(candidate.representative.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>remaining)continue;
                    double score=quality(candidate.representative)+Math.min(8,(candidate.spans.size()-1)*3);
                    for(var prior:section) {
                        double overlap=similarity(candidate.representative,prior.representative);
                        // Closely worded changes of conditions deserve separate visibility, not a redundancy penalty.
                        if(!candidate.representative.guard().equals(prior.representative.guard()) && overlap>.65) score+=12;
                        else score-=overlap*45;
                    }
                    if(score>bestScore){best=candidate;bestScore=score;}
                }
                if(best==null)break;
                section.add(best);selected.add(best);pool.remove(best);
                remaining-=best.representative.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
        }
        return object("schema_version","learning-note-digest/1","method","source-grouping/1",
                "points",selected.stream().map(Group::json).toList(),"stats",object("candidate_count",candidates.size(),
                        "unique_count",groups.size(),"merged_count",candidates.size()-groups.size(),
                        "displayed_count",selected.size(),"omitted_count",groups.size()-selected.size()));
    }

    private static List<Span> extract(Source source,int baseOrder) {
        var result=new ArrayList<Span>();boolean code=false;int offset=0;
        for(String line:source.answer().split("\n",-1)) {
            String stripped=line.strip();int start=offset+line.indexOf(stripped),end=start+stripped.length();
            if(stripped.startsWith("```")||stripped.startsWith("~~~")) code=!code;
            else if(!code && !stripped.isBlank() && !stripped.startsWith("#") && !stripped.startsWith("|")
                    && !stripped.matches("^(研究报告|已支持[：:]?$|来源|参考资料|证据记录|范围说明|上一轮|说明：上述|未完成目标|https?://).*")
                    && !stripped.endsWith(":") && !stripped.endsWith("：")) {
                // Strip only an attribution prefix, retaining every factual/conditional clause after it.
                var prefix=Pattern.compile("^(?:已支持[：:]\\s*)?根据[^。\\n]{0,220}?（来源：https?://[^）]+）[：:]").matcher(stripped);
                if(prefix.find())start+=prefix.end();
                // A report's attached scope is indivisible: retain it whole or leave it in the original.
                int metadata=source.answer().indexOf("（适用版本：",start);
                boolean hasMetadata=metadata>=start && metadata<end;
                if(hasMetadata) {
                    if(presentationMetadata(source.answer().substring(metadata,end),source.question()))end=metadata;
                    else {add(result,source,start,end,baseOrder);offset+=line.length()+1;continue;}
                }
                String material=source.answer().substring(start,end);
                if(material.matches("^(题目|练习题|参考答案要点)[：:].*")) add(result,source,start,end,baseOrder);
                else {
                    int from=start,depth=0;
                    for(int i=start;i<end;i++) {
                        char c=source.answer().charAt(i);
                        if("（([【".indexOf(c)>=0)depth++;
                        if("）)]】".indexOf(c)>=0)depth=Math.max(0,depth-1);
                        if(depth==0 && "。！？".indexOf(c)>=0) {
                            int next=i+1;
                            String remainder=source.answer().substring(next,end).stripLeading();
                            // Keep pronouns and consequences attached to their antecedent, within the same paragraph.
                            if(remainder.matches("^(它|其|这|此时|否则|也就是说|但是|不过).*")) continue;
                            if(remainder.matches("^(因此|所以).*" ) && !remainder.matches("^(因此|所以).{1,45}(只要|只有|如果|若|当).*"))continue;
                            add(result,source,from,next,baseOrder);from=next;
                        }
                    }
                    add(result,source,from,end,baseOrder);
                }
            }
            offset+=line.length()+1;
            if(result.size()>=MAX_PER_SOURCE)break;
        }
        return result;
    }

    private static void add(List<Span> spans,Source source,int from,int to,int baseOrder) {
        if(spans.size()>=MAX_PER_SOURCE)return;
        String answer=source.answer();
        while(from<to && Character.isWhitespace(answer.charAt(from)))from++;
        while(from<to && Character.isWhitespace(answer.charAt(to-1)))to--;
        if(from==to)return;
        String text=answer.substring(from,to);
        int length=text.codePointCount(0,text.length());
        if(length<12||length>MAX_TEXT||text.matches("^(\\[来源|（适用版本|范围说明|说明：上述|上一轮|研究报告|生活类比|参考资料).*"))return;
        if(text.matches("^(以上|上述|本段)?代码为说明性示例[，,]未实际执行[。.]?$"))return;
        if(text.matches("^(依据|根据|请根据)[^。\\n]{0,120}(官方文档|官方网页)(?:（[^）]{0,50}）)?(?:回答|说明)[，,]?(?:而非其他来源)?[。.]?$"))return;
        if(text.endsWith(":")||text.endsWith("：")||text.matches("(?s).*https?://.*"))return;
        String category=text.matches("(?s).*(^题目[：:]|^练习[：:]|^练习题[：:]|^参考答案要点[：:]|未执行的说明性示例).*" )?"practice":
                text.matches("(?s).*(如果|只要|只有|除非|若|前提|条件|必须|不能|不可|不得|不支持|多个构造器|适用版本).*" )?"condition":"concept";
        String proposition=text.replaceFirst("^[（(][一二三四五六七八九十\\d]+[）)]\\s*","")
                .replaceFirst("^(?:官方)?文档(?:还)?指出[，,：:]\\s*","")
                .replaceFirst("^若", "如果").replace("需用", "需要用");
        String normalized=normalize(proposition);
        var critical=new TreeSet<String>();var matcher=CRITICAL.matcher(proposition);
        while(matcher.find()) critical.add(matcher.group());
        var condition=CONDITIONS.matcher(proposition);while(condition.find())critical.add(normalize(condition.group()));
        // The same sentence under a different versioned question/report is not an interchangeable occurrence.
        var versions=Pattern.compile("(?i)(?:[a-z][a-z .-]{0,25}\\s+v?\\d+(?:\\.\\d+)+|(?:Java|JDK|Python|Spring Boot)\\s+\\d+|版本\\s*\\d+(?:\\.\\d+)*)")
                .matcher(source.question()+"\n"+source.answer());
        while(versions.find())critical.add("version:"+normalize(versions.group()));
        spans.add(new Span(source,from,to,category,text,normalized,String.join("|",critical),grams(normalized),baseOrder+spans.size()));
    }
    private static String normalize(String text) {
        return text.replaceAll("\\[来源\\d+\\]","").replaceAll("^[（(]?[一二三四五六七八九十\\d]+[）)、.]\\s*","")
                .replaceAll("[\\s\\p{Punct}，。；：！？、（）【】“”‘’]","").toLowerCase(Locale.ROOT);
    }
    private static boolean presentationMetadata(String suffix,String question) {
        String prefix="（适用版本：未确定，仅描述引用快照；有效时间未确定；条件：";
        if(!suffix.startsWith(prefix))return false;
        int end=suffix.lastIndexOf(']');if(end<prefix.length())return false;
        try {
            var conditions=JSON.readTree(suffix.substring(prefix.length(),end+1));
            if(!conditions.isArray())return false;
            for(var item:conditions) {
                if(!item.isTextual())return false;
                String value=item.asText();
                // Only formatting and attribution boilerplate may be detached; material scope is kept whole.
                if(value.matches("(?s).*(\\d|版本|如果|只有|除非|前提|条件|必须|不能|不得|不支持|未注册|不适用|大于|小于|至少|至多|在|仅限|仅当).*"))return false;
                if(question.contains(value))continue;
                if(value.matches("^(依据|根据|来源限定为|请根据).{0,120}(官方文档|官方网页)(回答|说明|。)?$"))continue;
                if(value.matches("(?:最后)?(?:出)?一道练习[，。]?"))continue;
                if(Set.of("用中文说明","用中文解释","中文","并注明来源。","注明来源","说明性示例","标注为未执行的说明性示例","标注为自创练习","最小代码示例").contains(value))continue;
                return false;
            }
            return true;
        } catch(Exception invalid) {return false;}
    }
    private static Set<String> grams(String text) {
        var values=new HashSet<String>();int[] cp=text.codePoints().toArray();
        for(int i=0;i+2<cp.length;i++)values.add(new String(cp,i,3));
        return values;
    }
    private static boolean duplicates(Span a,Span b) {
        if(a.normalized().equals(b.normalized()))return true;
        if(Math.min(a.normalized().length(),b.normalized().length())<36)return false;
        double ratio=(double)Math.min(a.normalized().length(),b.normalized().length())/Math.max(a.normalized().length(),b.normalized().length());
        return ratio>=.80 && similarity(a,b)>=.82;
    }
    private static double similarity(Span a,Span b) {
        int intersection=0;for(String gram:a.grams())if(b.grams().contains(gram))intersection++;
        int union=a.grams().size()+b.grams().size()-intersection;
        return union==0?0:(double)intersection/union;
    }
    private static double quality(Span span) {
        String text=span.text();double score=30-Math.min(360,text.length())/12.0;
        if(span.category().equals("concept") && text.matches("(?s).*(就是|是指|指的是|标识符|定义).*"))score+=25;
        if(text.startsWith("题目：")||text.startsWith("练习：")||text.startsWith("练习题："))score+=35;
        if(text.startsWith("参考答案要点"))score-=20;
        if(text.matches("^(因此|所以|也就是说|文档还|文档明确).*"))score-=3;
        return score-span.order()/10000.0;
    }

    private LearningNoteDigest() {}
}
