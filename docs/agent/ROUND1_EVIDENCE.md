# 第 1 轮 B：受控原文与裁决

状态：本地实现与隔离验收进行中。基线 `186a03f747951b40f7de284e6bd298a3f3eef94f`。本轮不部署，不进行真实模型采样；第 0 轮冻结文件不改。

## 接口与职责

`com.deepresearch.evidence` 提供 EvidenceService、原文 reader、JdbcEvidenceStore、裁决器与独立 Controller。A 控制层实现 EvidenceAuthority：认证、明确项目归属、当前 claim/lease/task scope、统一预算、已完成搜索候选、read_source 回执完成，以及核查模型输入/输出 hash 回执。没有该实现时全部拒绝。`deepresearch.agent.evidence.enabled=true` 才注册服务/Controller；通用 SecurityConfig 的路由准入由 A 对接。

内网端点 `/internal/agent/evidence`：`POST /read`、`/checks/prepare`、`/checks/complete`、`/packets`、`/publish`。请求均含 `identifiers={project_id,run_id,task_id,call_id,claim_token}`，不含自行授予的 owner/tenant。其他字段见 EvidenceDtos。来源只传 source_id，URL/KB 定位从当前 run 已完成且已授权的候选回执取得。

Python `deepresearch_workflow.evidence_check` 仅提供 `build_verifier_messages(request,request_sha256)`、`response_schema(request)` 和 `parse_verifier_response(raw,request,request_sha256)`。它不调用模型、工具、HTTP 或数据库。A 在其持久预算入口执行核查；B complete 必须取得绑定 check/request/response 的已完成模型回执。

PrepareRequest 有 claims（最多四个，text/kind/applicability）、evidence_ids（最多四个）、dispute_round（0/1/2）和 parent_check_id。空证据仍为中性核查请求，不能伪造一次模型调用。requires_model=false 仅表示已有完成记录可重放。请求/响应各限 65536 UTF-8 字节。语义核查不保证自然语言事实为真。

## 读取与来源

网页仅 HTTP(S) 默认端口，拒绝 URL 凭据/片段/控制字符。每跳重新解析、拒绝任何非公网地址，连接固定到已校验地址；TLS 仍按原主机验证证书/SNI。超时 12 秒，最多三次重定向，响应正文 262144 字节，header 16384 字节。不携带 Cookie/Authorization/系统代理，不接收压缩正文。地址策略参考 [IANA IPv4](https://www.iana.org/assignments/iana-ipv4-special-registry/) 与 [IPv6](https://www.iana.org/assignments/iana-ipv6-special-registry/)，采用更保守的公网单播集合。

只支持 UTF-8 HTML/text；HTML 提取正文、忽略 head/script/style，不执行 JavaScript。快照绑定提取后的原始文档文字，另存原响应字节 hash。超过 10000 code points 的窗口显式为 document_chunk；它不是完整全文，也不是搜索摘要。RAGFlow 读取既有配置 allowlist 与 active registry 中的完整分片，核对 document/chunk 身份；不会把搜索的 700 字摘要换名为原文。跨分片限定条件和动态网页渲染仍是限制。

原文显式 `Version:`/`Document version:`/`版本:` 声明可以记录版本；没有或冲突则 unknown。发表时间保持 unknown，不用抓取时间代替。来源组仅用相同文本文档 hash 保守识别重复，无法据此断言不同 hash 的来源相互独立；不做多数票真伪判断。

## 裁决与发布

模型返回每条 Claim × 每份 Evidence 的关系与完整段落引文。Java/Python 分别校验 ID、codepoint 范围、UTF-8 hash 和段落上下文，防止摘掉同段限定词。服务器按显式版本排除不适用来源；未确定所需版本时保留缺口。相反的适用来源默认 contested，只有 A 提供的已完成受控观察能在其记录范围内作为额外依据；标题/渠道/转载数量不证明真伪。

支持、反驳、不足与争议保存为不可变 Claim/Decision；记录采纳、弃用与未解决证据及原因。Challenge 带具体 search/read/recheck/counterevidence 建议；最多两轮补查后保留 unresolved/stop_with_gaps。相同 scoped Claim 集的调查指纹与 SQL 唯一键避免用新 call_id 重置轮次。旧结果不覆盖，新 check 通过 parent_check_id 保留纠正历史。

B publish 验证当前 run 原文回执、hash、范围和支持状态；KB 还读取 live chunk 再校验。A 的 publish_evidence 授权必须为这些有限读取预留工具预算。网页使用该 run 的不可变完成回执，没有额外无预算抓取。返回的 answer 只由通过核查的 Claim 原文和编号引用构成，A 必须发布该精确正文；额外模型叙述要先成为新 Claim 并核查。`semantic_truth_guaranteed=false` 始终保留。

## 持久化与隔离

V18 新增原文回执、冻结记录和核查表；事务锁定当前 run 的 owner/claim/lease/deadline/cancel 状态，再与 A 回执完成同事务提交。provider 读取在事务外进行。完成记录与内容禁止 UPDATE；幂等冲突拒绝。读取执行中但结果不确定时不盲目重试。父搜索回执与项目 ACL 由 A EvidenceAuthority 核验，不接收 fixture 的 authorized=true。V18 不伪造 V17；A 项目表/授权适配在联合验收时接通。

这里没有长期记忆 CRUD/语义索引/删除传播。真实网络、模型能力、完整 A 控制层接线与有限实测结果将在固定候选后独立记录；本轮合成 transport/model receipt 只验证实际服务与持久化路径。
