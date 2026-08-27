CREATE ROLE deepresearch_workflow LOGIN PASSWORD 'workflow-integration-test-password';
GRANT CONNECT ON DATABASE deepresearch TO deepresearch_workflow;
