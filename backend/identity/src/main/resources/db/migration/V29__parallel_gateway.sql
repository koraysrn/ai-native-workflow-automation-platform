-- V29: Parallel gateway bookkeeping on node executions.
-- branch_token_count  = branches opened by a PARALLEL_GATEWAY
-- merged_branch_count = branches that have reached the owning MERGE_GATEWAY
-- join_node_id        = the MERGE_GATEWAY node id a branch must report to
ALTER TABLE node_executions ADD COLUMN branch_token_count INTEGER;
ALTER TABLE node_executions ADD COLUMN merged_branch_count INTEGER;
ALTER TABLE node_executions ADD COLUMN join_node_id VARCHAR(100);
