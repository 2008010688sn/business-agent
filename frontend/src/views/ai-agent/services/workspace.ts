import type { PageResponse } from './common';
import type { RuntimeRunResp } from './runtimeRun';

export interface WorkspaceEmployee {
  id?: string;
  name?: string;
  status?: string;
}

const agentWorkspaceService = {
  async listEmployees(_workspaceId: string): Promise<WorkspaceEmployee[]> {
    return [];
  },
  async queryRunPage(
    _workspaceId: string,
    query: { current?: number; size?: number }
  ): Promise<PageResponse<RuntimeRunResp[]>> {
    return {
      success: true,
      message: 'ok',
      data: [],
      total: 0,
      pageNum: query.current || 1,
      pageSize: query.size || 20,
      totalPages: 0
    };
  }
};

export default agentWorkspaceService;
