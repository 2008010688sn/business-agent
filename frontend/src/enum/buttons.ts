export enum UserButtonsPermission {
  create = 'user:create',
  createTenantAdmin = 'user:create:tenant:admin',
  export = 'user:export',
  refresh = 'user:refresh',
  detail = 'user:detail',
  permissionDetail = 'user:permission:detail',
  modify = 'user:modify',
  disable = 'user:disable',
  enable = 'user:enable',
  unfreeze = 'user:unfreeze',
  resetPassword = 'user:reset-password'
}
export enum OrgButtonsPermission {
  create = 'org:create',
  modify = 'org:modify',
  delete = 'org:delete'
}
export enum RoleButtonsPermission {
  create = 'role:create',
  modify = 'role:modify',
  delete = 'role:delete',
  disable = 'role:disable',
  enable = 'user:enable',
  userAssign = 'role:user:assign',
  changeRole = 'role:user:change-role',
  removeRole = 'role:user:remove-user',
  resourceModify = 'role:resource:modify',
  dataPermissionModify = 'role:data-permission:modify'
}
export enum PositionButtonsPermission {
  create = 'position:create',
  modify = 'position:modify',
  delete = 'position:delete',
  disable = 'role:disable',
  enable = 'user:enable'
}
export enum SiteButtonsPermission {
  approvalProgress = 'site:approval-progress',
  approvalList = 'site:authorization-list',
  create = 'site:create',
  modify = 'site:modify',
  export = 'site:export',
  grant = 'site:grant',
  siteList = 'site:list',
  disable = 'site:disable',
  enable = 'site:enable',
  grantCreate = 'site:grant:create',
  grantModify = 'site:grant:modify',
  grantDelete = 'site:grant:delete',
  authorizationListRevoke = 'site:authorization-list:revoke',
  authorizationListRestart = 'site:authorization-list:restart'
}
/** 需求列表 */
export enum orderListButtonsPermission {
  import = 'demand:import',
  create = 'demand:create',
  customerCreate = 'demand:customer-order:create',
  customerApproval = 'demand:approval-config',
  export = 'demand:export',
  cancel = 'demand:cancel'
}
/** 分仓调度-调度管理 */
export enum dispatchButtonsPermission {
  export = 'demand:dispatch:export',
  combine = 'demand:dispatch:combine',
  sign = 'demand:dispatch:sign',
  plan = 'demand:dispatch:plan',
  recordExport = 'demand:dispatch:record:export',
  recordCancel = 'demand:dispatch:record:cancel'
}
/** 物流执行-物流执行 */
export enum logexecuteButtonsPermission {
  excute = 'order:pending-page:excute',
  executor = 'order:pending-page:change-executor',
  cancel = 'orders:execute-page:cancel',
  changeExecutor = 'order:execute-page:change-executor',
  feedback = 'orders:pending-out-page:logistics-feedback',
  entrust = 'orders:pending-out-page:project-entrust',
  outPrint = 'orders:pending-out-page:stock-out-print',
  insuranceAuto = 'orders:pending-out-page:insurance-auto',
  historyExport = 'orders:pending-out-page:history-detail-export',
  outExcute = 'orders:pending-out-page:stock-out-execute',
  entrustPlat = 'orders:pending-out-page:project-entrust:plat',
  entrustCompany = 'orders:pending-out-page:project-entrust:company',
  executeLogistics = 'orders:pending-out-page:stock-out-execute:logistics',
  executeCycle = 'orders:pending-out-page:stock-out-execute:cycle',
  executeZheJiang = 'orders:pending-out-page:stock-out-execute:zhejiang',
  feedbacks = 'orders:transport-page:logistics-feedback',
  stockPrint = 'orders:transport-page:stock-out-print',
  insuranceAutos = 'orders:transport-page:insurance-auto',
  historyExports = 'orders:transport-page:history-detail-export',
  sign = 'orders:transport-page:sign',
  fee = 'orders:transport-page:change-fees',
  signPrint = 'orders:sign-page:print',
  changeSign = 'orders:sign-page:change-sign'
}
/** 物流执行-运力准入-承运商管理 */
export enum carrierButtonsPermission {
  export = 'carriers:export',
  create = 'carriers:create',
  moidfy = 'carriers:modify',
  disable = 'carriers:disable',
  enable = 'carriers:enable',
  exports = 'cars:export',
  creates = 'cars:create',
  moidfys = 'cars:modify',
  disables = 'cars:disable',
  enables = 'cars:enable'
}
/** 物流执行-作业单调整 */
export enum costButtonsPermission {
  create = 'order-changes:create'
}
/** 仓储管理-维修管理 */
export enum applyButtonsPermission {
  export = 'spare-part-apply:export',
  create = 'spare-part-apply:create',
  repairsExport = 'assets-part-repairs:export',
  detoryExport = 'spare-part-destroy:export',
  detoryCreate = 'spare-part-destroy:create'
}
/** 仓储管理-盘点管理 */
export enum taskButtonsPermission {
  export = 'inventory-tasks:export',
  create = 'inventory-tasks:create',
  modify = 'inventory-tasks:modify',
  delete = 'inventory-tasks:delete',
  detailsExport = 'inventory-task-details:export'
}
/** 应付结算-复合仓账单 */
export enum expensebillButtonsPermission {
  export = 'fee-site:export',
  create = 'fee-site:create',
  modify = 'fee-site:modify',
  delete = 'fee-site:delete',
  approval = 'fee-site:approval'
}
/** 履约数仓-扫码数据 */
export enum scanButtonsPermission {
  quality = 'scan-orders:change-data-quality',
  record = 'scan-orders:record',
  export = 'scan-orders:export'
}
/** 履约数仓-履约数据 */
export enum businessButtonsPermission {
  export = 'order-products:export'
}
/** 应收结算-应收设置-客户结算规则 */
export enum rulesButtonsPermission {
  create = 'contract:create',
  export = 'contract:export'
}
/** 应收结算-应收设置-额外费用设置 */
export enum receivableCostButtonsPermission {
  create = 'additional-setting:create',
  export = 'additional-setting:export',
  stop = 'additional-setting:stop'
}
/** 应收结算-快速出账 */
export enum expenditureButtonsPermission {
  quickExport = 'quick-bill:export',
  quickGen = 'quick-bill:confirm-gen',
  billExport = 'bill-costs:export',
  addCreate = 'additional:create',
  addExport = 'additional:export',
  overExport = 'overdue-costs:export',
  dailyExport = 'daily-expenses:export'
}
/** 应收结算-对账审核 */
export enum reviewButtonsPermission {
  projectExport = 'bill-cost:approval:project:export',
  projectPass = 'bill-cost:approval:project:pass',
  projectReject = 'bill-cost:approval:project:reject',
  financeExport = 'bill-cost:approval:finance:export',
  customerExport = 'bill-cost:approval:customer:export',
  financePass = 'bill-cost:approval:finance:pass',
  financeReject = 'bill-cost:approval:finance:reject',
  confirm = 'bill-cost:approval:customer:confirm',
  customerReject = 'bill-cost:approval:customer:reject'
}
/** 发票中心 */
export enum invoiceButtonsPermission {
  applyCreate = 'invoice:apply:create',
  applyExport = 'invoice:apply:export',
  applyConfig = 'invoice:apply:industry-config',
  approval = 'invoice:approval:approval',
  approvalExport = 'invoice:approval:export',
  invoice = 'invoice:approval:submit-invoice',
  cancel = 'invoice:cancel',
  detailExport = 'invoice:detail-export',
  invoiceExport = 'invoice:invalidated-detail-export',
  redExport = 'invoice:red-flush-detail-export',
  redFlush = 'invoice:red-flush',
  import = 'invoice:import:import',
  importDelete = 'invoice:import:delete',
  create = 'invoice-unit:create',
  modify = 'invoice-unit:modify',
  collagentCreate = 'invoice:collagent:create',
  collagentModify = 'invoice:collagent:modify',
  collagentDelete = 'invoice:collagent:delete',
  collagentDisable = 'invoice:collagent:disable',
  collagentEnable = 'invoice:collagent:enable',
  collagentExport = 'invoice:collagent:export'
}
/** 应付结算-合同管理 */
export enum contractButtonsPermission {
  spCreate = 'sp-contract:create',
  spExport = 'sp-contract:export',
  whExport = 'wh-contract:export',
  approvalPass = 'wh-contract:approval:approval-pass',
  approvalExport = 'wh-contract:approval:export',
  approvalNoPass = 'wh-contract:approval:approval-nopass',
  approvalAgain = 'sp-contract:approval:again',
  approvalCancel = 'sp-contract:approval:cancel',
  approval = 'sp-contract:approval:approval',
  export = 'sp-contract:approval:export'
}

/** 资产池-资产投放 */
export enum assetButtonsPermission {
  appalyCreate = 'asset:appaly:create',
  appalyModify = 'asset:appaly:modify',
  appalyDelete = 'asset:appaly:delete'
}

// 系统设置 - 团队管理
export enum teamButtonsPermission {
  // 团队
  createTeam = 'team:create',
  modifyTeam = 'team:modify',
  deleteTeam = 'team:delete',
  disableTeam = 'team:disable',
  enableTeam = 'team:enable',
  transferTeam = 'team:transfer',
  // 成员
  createMember = 'team:create:member',
  modifyMember = 'team:modify:member',
  deleteMember = 'team:delete:member',
  disableMember = 'team:disable:member',
  enableMember = 'team:enable:member',
  setTeamLeader = 'team:set:teamLeader',
  // 部门
  createCepartment = 'team:create:cepartment',
  modifyCepartment = 'team:modify:cepartment',
  deleteCepartment = 'team:delete:cepartment',
  disableCepartment = 'team:disable:cepartment',
  enableCepartment = 'team:enable:cepartment'
}
// 维修管理
export enum maintainButtonsPermission {
  download = 'maintain_appraisal-report:download',
  send = 'maintain_appraisal-report:send'
}
