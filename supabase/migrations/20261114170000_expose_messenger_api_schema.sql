-- Expose only the dedicated messenger API schema in addition to the project's
-- existing PostgREST schemas. Existing schemas are preserved exactly.
alter role authenticator set pgrst.db_schemas =
  'public, storage, graphql_public, private, messenger';

notify pgrst, 'reload config';
