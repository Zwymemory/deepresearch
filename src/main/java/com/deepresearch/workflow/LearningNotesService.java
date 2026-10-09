package com.deepresearch.workflow;

import com.deepresearch.security.AuthPrincipal;
import com.deepresearch.service.UserContextService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;
import static com.deepresearch.workflow.ResearchProgressSelectionDtos.*;
import static org.springframework.http.HttpStatus.*;

/** Project-scoped extractive learning episodes, derived from originals, never factual evidence. */
@Service
public class LearningNotesService {
    static final int CONTEXT_BYTES=32768, NOTE_BYTES=8192, CANDIDATE_LIMIT=100;
    private final JdbcTemplate db;
    private final UserContextService users;
    private final ResearchProgressRepository progress;
    @Value("${deepresearch.memory.auto-save.scheduler-enabled:true}") private boolean scheduled=true;
    public LearningNotesService(JdbcTemplate db,UserContextService users,ResearchProgressRepository progress) {
        this.db=db;this.users=users;this.progress=progress;
    }
    private static final String SOURCES="""
        SELECT w.run_id,w.session_id,w.question,w.status,w.final_response->>'answer' AS answer,
          w.context_snapshot::text AS context,w.created_at,a.project_id,a.tenant_id,a.owner_id,
          COALESCE(m.payload,'{}'::jsonb)::text AS progress
        FROM agent_workflow_run w JOIN agent_research_run a USING(run_id)
        JOIN research_project p ON p.project_id=a.project_id AND p.tenant_id=a.tenant_id AND p.owner_id=a.owner_id
        LEFT JOIN research_progress_memory m ON m.run_id=w.run_id AND m.project_id=a.project_id
        LEFT JOIN research_progress_auto_save s ON s.run_id=w.run_id
        WHERE w.user_id=a.tenant_id||':'||a.owner_id AND COALESCE(s.status,'')<>'DELETED'
          AND w.status IN ('SUCCEEDED','INSUFFICIENT_EVIDENCE')
          AND length(w.final_response->>'answer')>0
          AND (w.status='SUCCEEDED' OR (jsonb_typeof(w.final_response->'claims')='array' AND w.final_response->'claims'<>'[]'::jsonb))
        """;
    @Scheduled(fixedDelayString="${deepresearch.memory.auto-save.interval-ms:2000}")
    @Transactional
    public void reconcile() {
        if(!scheduled) return;
        var runs=db.queryForList("""
            SELECT w.run_id FROM agent_workflow_run w JOIN agent_research_run a USING(run_id)
            LEFT JOIN research_learning_entry e USING(run_id)
            LEFT JOIN research_progress_memory m ON m.run_id=w.run_id
            LEFT JOIN research_progress_auto_save s ON s.run_id=w.run_id
            WHERE w.context_snapshot->'project_progress_policy'->>'enabled'='true'
              AND COALESCE(s.status,'')<>'DELETED' AND w.status IN ('SUCCEEDED','INSUFFICIENT_EVIDENCE')
              AND length(w.final_response->>'answer')>0
              AND (w.status='SUCCEEDED' OR (jsonb_typeof(w.final_response->'claims')='array' AND w.final_response->'claims'<>'[]'::jsonb))
              AND (e.run_id IS NULL OR e.updated_at<w.updated_at OR e.updated_at<m.saved_at OR e.payload->>'extractor_version' IS DISTINCT FROM 'learning-excerpts/2')
            ORDER BY w.created_at,w.run_id LIMIT 20
            """,String.class);
        runs.forEach(this::capture);
    }
    /** Called at accepted-run boundaries too, so an immediate follow-up need not wait for the scheduler. */
    void catchUp(String project,AuthPrincipal principal) {
        progress.requireProject(project,principal);
        var rows=db.queryForList(SOURCES+" AND a.project_id=? AND a.tenant_id=? AND a.owner_id=? ORDER BY w.created_at DESC,w.run_id DESC LIMIT 100",
                project,principal.tenantId(),principal.userId());
        Collections.reverse(rows);
        rows.forEach(this::captureSource);
    }
    @Transactional
    public void capture(String run) {
        var rows=db.queryForList(SOURCES+" AND w.run_id=?",run);
        if(rows.size()==1) captureSource(rows.get(0));
    }
    private void captureSource(Map<String,Object> source) {
        String run=(String)source.get("run_id"),project=(String)source.get("project_id");
        // Serialize topic assignment with accepted-run creation and other note writers.
        db.queryForObject("SELECT project_id FROM research_project WHERE project_id=? FOR UPDATE",String.class,project);
        String hash=sourceHash(source);
        var old=db.queryForList("SELECT source_sha256,topic_id FROM research_learning_entry WHERE run_id=?",run);
        if(!old.isEmpty() && hash.equals(old.get(0).get("source_sha256"))) {
            db.update("UPDATE research_learning_entry SET updated_at=now() WHERE run_id=?",run);return;
        }
        String topic=old.isEmpty()?null:(String)old.get(0).get("topic_id");
        String question=(String)source.get("question");
        if(topic==null) {
            var topics=db.queryForList("""
                SELECT t.topic_id,t.title,max(e.source_created_at) AS last_at
                FROM research_learning_topic t JOIN research_learning_entry e USING(topic_id)
                WHERE t.project_id=? AND t.tenant_id=? AND t.owner_id=? AND NOT t.deleted
                GROUP BY t.topic_id ORDER BY last_at DESC LIMIT 30
                """,project,source.get("tenant_id"),source.get("owner_id"));
            int best=2;
            for(var candidate:topics) {
                int score=LearningNoteText.score(question,(String)candidate.get("title"));
                if(score>best){best=score;topic=(String)candidate.get("topic_id");}
            }
            if(topic==null && LearningNoteText.followup(question)) {
                var frozen=parse((String)source.get("context")).path("conversation_context").path("learning_notes");
                String previous=frozen.path("topic_id").asText();
                if(topics.stream().anyMatch(t->previous.equals(t.get("topic_id")))) topic=previous;
                else if(!topics.isEmpty()) topic=(String)topics.get(0).get("topic_id");
            }
            if(topic==null) {
                topic="learn-"+UUID.randomUUID();
                db.update("INSERT INTO research_learning_topic(topic_id,project_id,tenant_id,owner_id,title) VALUES (?,?,?,?,?)",
                        topic,project,source.get("tenant_id"),source.get("owner_id"),LearningNoteText.title(question));
            }
        }
        ObjectNode material=parse((String)source.get("progress")).deepCopy();material.put("learning_run_status",(String)source.get("status"));
        var body=(ObjectNode)LearningNoteText.extract(question,(String)source.get("answer"),material);
        body.put("question",question).put("answer_sha256",sha((String)source.get("answer"))).put("run_status",(String)source.get("status"))
                .put("extractor_version","learning-excerpts/2");
        db.update("""
            INSERT INTO research_learning_entry(run_id,project_id,tenant_id,owner_id,topic_id,source_sha256,payload,source_created_at)
            VALUES (?,?,?,?,?,?,?::jsonb,?) ON CONFLICT(run_id) DO UPDATE SET
              source_sha256=EXCLUDED.source_sha256,payload=EXCLUDED.payload,updated_at=now()
            """,run,project,source.get("tenant_id"),source.get("owner_id"),topic,hash,canonical(body),source.get("created_at"));
    }
    private String sourceHash(Map<String,Object> source) {
        return sha(canonical(object("extractor_version","learning-excerpts/2","question",source.get("question"),"answer",source.get("answer"),
                "status",source.get("status"),"correction",parse((String)source.get("progress")).path("user_correction").asText(""))));
    }
    private List<Map<String,Object>> entries(String project,AuthPrincipal principal) {
        var stored=db.queryForList("""
            SELECT e.run_id,e.topic_id,e.source_sha256,e.payload::text AS payload,t.title,t.correction,t.revision,
              e.source_created_at FROM research_learning_entry e JOIN research_learning_topic t USING(topic_id)
            WHERE e.project_id=? AND e.tenant_id=? AND e.owner_id=? AND NOT t.deleted
            ORDER BY e.source_created_at DESC,e.run_id DESC LIMIT 100
            """,project,principal.tenantId(),principal.userId());
        var result=new ArrayList<Map<String,Object>>();
        for(var row:stored) {
            var source=db.queryForList(SOURCES+" AND w.run_id=? AND a.project_id=? AND a.tenant_id=? AND a.owner_id=?",
                    row.get("run_id"),project,principal.tenantId(),principal.userId());
            if(source.size()!=1 || !sourceHash(source.get(0)).equals(row.get("source_sha256"))) continue;
            row.put("source",source.get(0));result.add(row);
        }
        return result;
    }
    @Transactional(readOnly=true)
    public JsonNode list(String project) {
        var p=users.currentPrincipalRequired();progress.requireProject(project,p);
        var grouped=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(var row:entries(project,p)) grouped.computeIfAbsent((String)row.get("topic_id"),k->new ArrayList<>()).add(row);
        var items=JSON.createArrayNode();grouped.values().forEach(rows->items.add(topicView(project,rows)));
        return object("schema_version","learning-notes-view/1","project_id",project,"items",items,
                "candidate_limit",CANDIDATE_LIMIT,"summary_method","source-excerpts/1","trusted_as_evidence",false);
    }
    private JsonNode topicView(String project,List<Map<String,Object>> rows) {
        var first=rows.get(0);var history=JSON.createArrayNode();var discussion=JSON.createArrayNode();
        var gaps=JSON.createArrayNode();var seen=new HashSet<String>();
        for(var row:rows) {
            var body=parse((String)row.get("payload"));String run=(String)row.get("run_id");
            for(var gap:body.path("open_questions")) if(gaps.size()<8 && seen.add(gap.path("text").asText()))
                gaps.add(object("run_id",run,"text",gap.path("text"),"kind",gap.path("kind")));
            history.add(object("run_id",run,"question",body.path("question"),"has_exercise",body.path("has_exercise"),
                    "has_code",body.path("has_code"),"run_status",body.path("run_status"),"created_at",timestamp(row.get("source_created_at")),
                    "source_url","/api/research/projects/"+project+"/learning-notes/sources/"+run));
        }
        var digest=LearningNoteDigest.create(digestSources(rows),8);
        // Old clients still receive the existing shape, now with the same curated source points.
        for(var point:digest.path("points")) discussion.add(object("run_id",point.path("representative").path("run_id"),"text",point.path("text")));
        // A source that cannot safely be split still has its original legacy excerpts and full Q&A.
        if(discussion.isEmpty()) {
            var seenExcerpts=new HashSet<String>();
            for(var row:rows) for(var excerpt:parse((String)row.get("payload")).path("discussed")) {
                String text=excerpt.path("text").asText();
                if(discussion.size()<8 && !text.isBlank() && seenExcerpts.add(text))
                    discussion.add(object("run_id",row.get("run_id"),"text",text));
            }
        }
        return object("topic_id",first.get("topic_id"),"title",first.get("title"),"revision",first.get("revision"),
                "correction",first.get("correction"),"entry_count",rows.size(),"discussed",discussion,
                "open_questions",gaps,"entries",history,"mastery","unknown","digest",digest);
    }
    @Transactional(readOnly=true)
    public JsonNode source(String project,String run) {
        var p=users.currentPrincipalRequired();progress.requireProject(project,p);
        var matching=entries(project,p).stream().filter(r->run.equals(r.get("run_id"))).findFirst().orElseThrow(()->new ResponseStatusException(NOT_FOUND));
        var original=sourceOf(matching);
        return object("run_id",run,"question",original.get("question"),"answer",original.get("answer"),
                "answer_sha256",sha((String)original.get("answer")),"trusted_as_evidence",false);
    }
    @Transactional
    public JsonNode correct(String project,String topic,String note) {
        if(note==null || note.codePointCount(0,note.length())>2000) throw new ResponseStatusException(BAD_REQUEST,"学习备注最多2000字");
        var p=users.currentPrincipalRequired();progress.requireProject(project,p);
        if(db.update("UPDATE research_learning_topic SET correction=?,revision=revision+1 WHERE topic_id=? AND project_id=? AND tenant_id=? AND owner_id=? AND NOT deleted",
                note,topic,project,p.tenantId(),p.userId())!=1) throw new ResponseStatusException(NOT_FOUND);
        return list(project);
    }
    @Transactional
    public JsonNode delete(String project,String topic) {
        var p=users.currentPrincipalRequired();progress.requireProject(project,p);
        if(db.update("UPDATE research_learning_topic SET deleted=true,revision=revision+1 WHERE topic_id=? AND project_id=? AND tenant_id=? AND owner_id=? AND NOT deleted",
                topic,project,p.tenantId(),p.userId())!=1) throw new ResponseStatusException(NOT_FOUND);
        return object("topic_id",topic,"deleted",true);
    }
    /** Freeze one relevant topic and at most two original reports; the archive is not the prompt. */
    JsonNode freeze(String project,String session,String question,AuthPrincipal principal) {
        var rows=entries(project,principal);
        if(rows.isEmpty()) return null;
        String selected=null;int best=2;
        for(var row:rows) {
            int score=LearningNoteText.score(question,(String)row.get("title"));
            score=Math.max(score,LearningNoteText.score(question,parse((String)row.get("payload")).path("question").asText()));
            if(score>best){best=score;selected=(String)row.get("topic_id");}
        }
        // Merely saying "give an exercise" or "do not give an exercise" is not an old referent.
        if(selected==null && LearningNoteText.followup(question)) selected=(String)rows.get(0).get("topic_id");
        if(selected==null) return null;
        final String topic=selected;
        var relevant=new ArrayList<>(rows.stream().filter(r->topic.equals(r.get("topic_id"))).toList());
        var first=relevant.get(0);
        boolean exercise=LearningNoteText.exercise(question)||question.contains("这道题")||question.contains("那道题");
        var ranked=new ArrayList<>(relevant);
        ranked.sort(Comparator.comparingInt((Map<String,Object> row)->{
            var body=parse((String)row.get("payload"));
            return LearningNoteText.score(question,body.path("question").asText())
                    +(exercise && body.path("has_exercise").asBoolean()?100:0);
        }).reversed());
        var originals=ranked.subList(0,Math.min(2,ranked.size()));
        var reports=JSON.createArrayNode();var refs=new LinkedHashMap<String,JsonNode>();
        for(var row:originals) {
            reports.add(ConversationReferents.report(sourceOf(row),"learning_project"));
            refs.put((String)row.get("run_id"),reference(row));
        }
        var summaries=JSON.createArrayNode();
        // Prefer the selected originals and latest observations; rebuilding never consumes an old summary.
        var chosen=new ArrayList<>(originals);for(var row:relevant) if(!chosen.contains(row)) chosen.add(row);
        var digest=LearningNoteDigest.create(digestSources(chosen.subList(0,Math.min(5,chosen.size()))),8,3600);
        for(var row:chosen) {
            if(summaries.size()>=5) break;
            var body=parse((String)row.get("payload"));
            var excerpts=JSON.createArrayNode();
            for(var point:digest.path("points")) {
                var representative=point.path("representative");
                if(row.get("run_id").equals(representative.path("run_id").asText()))
                    excerpts.add(object("text",point.path("text"),"start",representative.path("start"),"end",representative.path("end")));
            }
            var item=object("run_id",row.get("run_id"),"question",LearningNoteText.shortText(body.path("question").asText(),200),
                    "discussed",excerpts,"open_questions",body.path("open_questions"),"user_correction",body.path("user_correction"),
                    "has_exercise",body.path("has_exercise"),"has_code",body.path("has_code"));
            summaries.add(item);refs.put((String)row.get("run_id"),reference(row));
            if(bytes(summaries)>5000 && summaries.size()>2) {summaries.remove(summaries.size()-1);refs.remove((String)row.get("run_id"));break;}
        }
        var notes=object("schema_version","learning-note-context/1","topic_id",topic,"title",first.get("title"),
                "correction",first.get("correction"),"mastery","unknown","summary_method",LearningNoteDigest.METHOD,
                "total_entries",relevant.size(),"entries",summaries,"source_refs",refs.values());
        // A long correction is a hard constraint: never shorten or silently omit it.
        if(bytes(notes)>NOTE_BYTES) throw memoryError("INPUT_TOO_LARGE");
        var result=object("schema_version","conversation-referents/2","trusted_as_evidence",false,"reports",reports,"learning_notes",notes);
        if(bytes(result)>CONTEXT_BYTES) throw memoryError("INPUT_TOO_LARGE");
        return result;
    }
    private JsonNode reference(Map<String,Object> row) {
        return object("run_id",row.get("run_id"),"source_sha256",row.get("source_sha256"),
                "topic_id",row.get("topic_id"),"topic_revision",row.get("revision"));
    }
    private static List<LearningNoteDigest.Source> digestSources(List<Map<String,Object>> rows) {
        return rows.stream().map(row->{var source=sourceOf(row);return new LearningNoteDigest.Source(
                (String)row.get("run_id"),(String)source.get("question"),(String)source.get("answer"));}).toList();
    }
    @Transactional(readOnly=true)
    public JsonNode used(String run) {
        var principal=users.currentPrincipalRequired();String project=progress.projectForRun(run,principal);
        var snapshot=parse(db.queryForObject("SELECT context_snapshot::text FROM agent_workflow_run WHERE run_id=?",String.class,run));
        var conversation=snapshot.path("conversation_context");
        if(!"conversation-referents/2".equals(conversation.path("schema_version").asText()))
            return object("status","EMPTY","project_id",project);
        boolean accessible=accessible(project,principal,conversation);
        return object("status",accessible?"SELECTED":"UNAVAILABLE","project_id",project,
                "learning_notes",accessible?conversation.path("learning_notes"):null,"trusted_as_evidence",false);
    }
    @Transactional
    public ValidationResult validate(ValidationRequest request) {
        var runs=db.queryForList("""
            SELECT a.project_id,a.tenant_id,a.owner_id,w.context_snapshot::text AS context
            FROM agent_workflow_run w JOIN agent_research_run a USING(run_id)
            WHERE w.run_id=? AND w.user_id=a.tenant_id||':'||a.owner_id FOR SHARE OF w
            """,request.runId());
        if(runs.size()!=1) throw memoryError("NOT_FOUND");
        var run=runs.get(0);String project=(String)run.get("project_id");
        var principal=new AuthPrincipal((String)run.get("tenant_id"),(String)run.get("owner_id"),List.of("USER"));
        progress.requireProject(project,principal);
        var snapshot=parse((String)run.get("context"));var conversation=snapshot.path("conversation_context");
        var binding=snapshot.path("learning_memory_binding");String hash=sha(canonical(conversation));
        if(!"conversation-referents/2".equals(conversation.path("schema_version").asText())
                || !hash.equals(request.projectionSha256())||!hash.equals(binding.path("projection_sha256").asText())
                ||!project.equals(binding.path("project_id").asText())||bytes(conversation)!=binding.path("canonical_bytes").asInt()
                ||bytes(conversation)>CONTEXT_BYTES) throw memoryError("INVALID");
        if(!accessible(project,principal,conversation)) throw memoryError("REVOKED");
        var checked=db.query("""
            SELECT clock_timestamp() AS checked_at FROM agent_workflow_run w
            JOIN agent_workflow_grant g ON g.run_id=w.run_id AND g.grant_id=w.grant_id AND g.subject=w.user_id
            WHERE w.run_id=? AND w.claim_token=? AND w.user_id=? AND w.lease_until>clock_timestamp()
              AND w.deadline_at>clock_timestamp() AND NOT w.cancel_requested
              AND g.expires_at>clock_timestamp() AND g.revoked_at IS NULL
              AND w.status IN ('QUEUED','PLANNING','WORKING','REVIEWING','SYNTHESIZING')
            """,(rs,n)->rs.getObject("checked_at",OffsetDateTime.class).toInstant(),request.runId(),request.claimToken(),principal.storageUserId());
        if(checked.size()!=1) throw memoryError("CLAIM_INVALID");
        return new ValidationResult(project,hash,checked.get(0));
    }
    private boolean accessible(String project,AuthPrincipal p,JsonNode conversation) {
        var refs=conversation.path("learning_notes").path("source_refs");
        if(!refs.isArray()||refs.isEmpty()||refs.size()>5) return false;
        var current=new HashMap<String,Map<String,Object>>();entries(project,p).forEach(r->current.put((String)r.get("run_id"),r));
        var seen=new HashSet<String>();
        for(var ref:refs) {
            var row=current.get(ref.path("run_id").asText());
            if(row==null||!seen.add(ref.path("run_id").asText())||!canonical(reference(row)).equals(canonical(ref))) return false;
        }
        for(var report:conversation.path("reports")) if(!seen.contains(report.path("run_id").asText())) return false;
        return true;
    }
    private static ResearchMemoryException memoryError(String suffix) {return new ResearchMemoryException("RESEARCH_MEMORY_"+suffix,true);}
    static int bytes(JsonNode node) {return canonical(node).getBytes(StandardCharsets.UTF_8).length;}
    private static String timestamp(Object value) {
        if(value instanceof java.sql.Timestamp time) return time.toInstant().toString();
        if(value instanceof OffsetDateTime time) return time.toInstant().toString();
        throw new IllegalStateException("Missing learning source timestamp");
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> sourceOf(Map<String,Object> row) {return (Map<String,Object>)row.get("source");}
    private static JsonNode parse(String value) {try{return JSON.readTree(value);}catch(Exception e){throw new IllegalStateException("Invalid stored learning note",e);}}
}
