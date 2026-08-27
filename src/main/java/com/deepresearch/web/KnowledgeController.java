package com.deepresearch.web;

import com.deepresearch.service.KnowledgeBaseService;
import com.deepresearch.web.dto.IngestRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Week4：知识库管理接口。
 *
 *  - POST   /api/kb/ingest  把一段文本切片向量化后入库
 *  - POST   /api/kb/documents/file  上传 txt/md/pdf 并入库
 *  - GET    /api/kb/documents  查看文档列表
 *  - GET    /api/kb/documents/{docId} 查看文档详情和 chunk 摘要
 *  - DELETE /api/kb/documents/{docId} 删除单个文档和双索引
 *  - POST   /api/kb/documents/{docId}/reindex 重建单文档索引
 *  - POST   /api/kb/reindex-keyword  把 pgvector 里的已有分片重建到 Elasticsearch 关键词索引
 *  - GET    /api/kb/count   查看库里有多少分片
 *  - DELETE /api/kb         清空知识库
 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeController {

    private final KnowledgeBaseService kb;

    public KnowledgeController(KnowledgeBaseService kb) {
        this.kb = kb;
    }

    @PostMapping("/ingest")
    public Object ingest(@RequestBody @Valid IngestRequest request) {
        return kb.ingest(request.title(), request.text());
    }

    @PostMapping("/documents/file")
    public Object ingestFile(@RequestParam("file") MultipartFile file,
                             @RequestParam(value = "title", required = false) String title) throws IOException {
        return kb.ingestFile(title, file.getOriginalFilename(), file.getBytes());
    }

    @GetMapping("/documents")
    public List<?> documents() {
        return kb.listDocuments();
    }

    @GetMapping("/documents/{docId}")
    public Object document(@PathVariable String docId) {
        return kb.getDocument(docId);
    }

    @DeleteMapping("/documents/{docId}")
    public Object deleteDocument(@PathVariable String docId) {
        return kb.deleteDocument(docId);
    }

    @PostMapping("/documents/{docId}/reindex")
    public Object reindexDocument(@PathVariable String docId) {
        return kb.reindexDocument(docId);
    }

    @GetMapping("/count")
    public Map<String, Object> count() {
        return Map.of("total", kb.count());
    }

    @PostMapping("/reindex-keyword")
    public Map<String, Object> reindexKeyword() {
        int chunks = kb.reindexKeywordIndex();
        return Map.of("keywordIndexed", chunks,
                "total", kb.count());
    }

    @DeleteMapping
    public Map<String, Object> clear() {
        kb.clear();
        return Map.of("total", kb.count());
    }
}
