import type { Datasource } from '@/views/ai-agent/services/datasource';

/**
 * 读接口不再回显 connectionUrl（内网库拓扑属敏感信息），展示用的连接地址一律由
 * host/port/databaseName 现拼。
 */
export function formatDatasourceEndpoint(datasource?: Datasource | null): string {
  if (!datasource) {
    return '';
  }

  const endpoint = datasource.host ? `${datasource.host}${datasource.port ? `:${datasource.port}` : ''}` : '';
  const database = datasource.databaseName ? `${endpoint ? '/' : ''}${datasource.databaseName}` : '';
  return `${endpoint}${database}`;
}
