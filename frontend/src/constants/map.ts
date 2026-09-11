/** 合同类型映射 */
export const CONTRACT_TYPE_MAP = {
  0: '买卖',
  1: '年租',
  2: '趟租',
  4: '超期费',
  11: '额外费用',
  20: '箱天',
  30: '单据日租'
};

/** 结算节点映射 */
export const SETTLEMENT_NODE_MAP = {
  0: '送箱结算',
  1: '重箱结算',
  3: '二段结算',
  4: '送重结算',
  5: '箱天结算',
  6: '单据日租结算'
};

/** 退回类型映射 */
export const RETURN_TYPE_MAP = {
  0: '送箱签收时异常',
  1: '送箱签收后异常',
  2: '需求量减少',
  3: '合同到期',
  4: '合同期终止合作',
  5: '其他',
  6: '外观破损',
  7: '外观污渍'
};

export const Model = [
  {
    vehicleName: '厢式',
    vehicleType: '3',
    lengths: [
      { length: '3.60', lengthName: '3.60M', capacity: 10 },
      { length: '4.20', lengthName: '4.20M', capacity: 21.95 },
      { length: '7.70', lengthName: '7.70M', capacity: 49.05 },
      { length: '9.60', lengthName: '9.60M', capacity: 61.15 }
    ]
  },
  {
    vehicleName: '厢式（窄）',
    vehicleType: '7',
    lengths: [{ length: '4.20', lengthName: '4.20M', capacity: 20.04 }]
  },
  {
    vehicleName: '厢式（宽体）',
    vehicleType: '8',
    lengths: [{ length: '4.20', lengthName: '4.20M', capacity: 23.39 }]
  },
  {
    vehicleName: '平板',
    vehicleType: '1',
    lengths: [
      { length: '3.60', lengthName: '3.60M', capacity: 10 },
      { length: '4.20', lengthName: '4.20M', capacity: 21.95 },
      { length: '6.80', lengthName: '6.80M', capacity: 44.98 },
      { length: '7.70', lengthName: '7.70M', capacity: 50.94 },
      { length: '8.70', lengthName: '8.70M', capacity: 57.55 },
      { length: '9.60', lengthName: '9.60M', capacity: 63.5 },
      { length: '13.00', lengthName: '13.00M', capacity: 79.97 }
    ]
  },
  {
    vehicleName: '平板（窄）',
    vehicleType: '9',
    lengths: [{ length: '4.20', lengthName: '4.20M', capacity: 20.04 }]
  },
  {
    vehicleName: '飞翼',
    vehicleType: '4',
    lengths: [
      { length: '4.20', lengthName: '4.20M', capacity: 21.95 },
      { length: '7.70', lengthName: '7.70M', capacity: 48.11 },
      { length: '9.60', lengthName: '9.60M', capacity: 61.15 }
    ]
  },
  {
    vehicleName: '高栏',
    vehicleType: '2',
    lengths: [
      { length: '4.20', lengthName: '4.20M', capacity: 21.95 },
      { length: '6.80', lengthName: '6.80M', capacity: 44.98 },
      { length: '7.70', lengthName: '7.70M', capacity: 50.94 },
      { length: '8.70', lengthName: '8.70M', capacity: 57.55 },
      { length: '9.60', lengthName: '9.60M', capacity: 63.5 }
    ]
  },
  {
    vehicleName: '高栏（宽体）',
    vehicleType: '10',
    lengths: [{ length: '4.20', lengthName: '4.20M', capacity: 23.39 }]
  },
  {
    vehicleName: '高低板',
    vehicleType: '11',
    lengths: [
      { length: '13.00', lengthName: '13.00M', capacity: 82.1 },
      { length: '13.70', lengthName: '13.70M', capacity: 106.2 },
      { length: '17.50', lengthName: '17.50M', capacity: 138.6 }
    ]
  },
  {
    vehicleName: '高低板（高落差）',
    vehicleType: '12',
    lengths: [
      { length: '13.00', lengthName: '13.00M', capacity: 86.31 },
      { length: '13.70', lengthName: '13.70M', capacity: 111.78 },
      { length: '17.50', lengthName: '17.50M', capacity: 146.58 }
    ]
  }
];

export const PRODUCT_TYPE = [
  {
    productName: '1210围板箱',
    packingMethods: [
      {
        packingMethod: '小三明治',
        packingId: 'small_sandwiches',
        volume: 0.27
      },
      {
        packingMethod: '大三明治5个/托',
        packingId: 'big_sandwiches_unit_5',
        volume: 0.194
      },
      {
        packingMethod: '大三明治8个/托',
        packingId: 'big_sandwiches_unit_8',
        volume: 0.18
      },
      {
        packingMethod: '大三明治10个/托',
        packingId: 'big_sandwiches_unit_10',
        volume: 0.168
      },
      {
        packingMethod: '展开箱',
        packingId: 'expand_box',
        volume: '1.2 * 1',
        lengthWidth: 1.2,
        falg: true,
        height: '',
        total: ''
      }
    ]
  },
  {
    productName: '1210川字围板箱',
    packingMethods: [
      {
        packingMethod: '折叠',
        packingId: 'folding',
        volume: 0.3
      },
      {
        packingMethod: '展开箱',
        packingId: 'expand_box',
        volume: '1.215 * 1.015',
        lengthWidth: 1.233225,
        falg: true,
        height: '',
        total: ''
      }
    ]
  },
  {
    productName: '1109围板箱',
    packingMethods: [
      {
        packingMethod: '小三明治',
        packingId: 'small_sandwiches',
        volume: 0.26
      },
      {
        packingMethod: '折叠',
        packingId: 'folding',
        volume: 0.17
      },
      {
        packingMethod: '展开箱',
        packingId: 'expand_box',
        volume: '1.15 * 1',
        lengthWidth: 1.15,
        falg: true,
        height: '',
        total: ''
      }
    ]
  },
  {
    productName: '1150围板箱',
    packingMethods: [
      {
        packingMethod: '小三明治',
        packingId: 'small_sandwiches',
        volume: 0.3
      },
      {
        packingMethod: '折叠',
        packingId: 'folding',
        volume: 0.2
      },
      {
        packingMethod: '展开箱',
        packingId: 'expand_box',
        volume: '1.15 * 1.15',
        lengthWidth: 1.3225,
        falg: true,
        height: '',
        total: ''
      }
    ]
  },
  {
    productName: '1450围板箱',
    packingMethods: [
      {
        packingMethod: '小三明治',
        packingId: 'small_sandwiches',
        volume: 0.358875
      },
      {
        packingMethod: '大三明治',
        packingId: 'big_sandwiches',
        volume: 0.32625
      },
      {
        packingMethod: '展开箱',
        packingId: 'expand_box',
        volume: '1.45 * 1.125',
        lengthWidth: 1.63125,
        falg: true,
        height: '',
        total: ''
      }
    ]
  },
  {
    productName: '1208围板箱',
    packingMethods: [
      {
        packingMethod: '折叠',
        packingId: 'folding',
        volume: 0.156
      },
      {
        packingMethod: '展开箱',
        packingId: 'expand_box',
        volume: '1.2 * 0.8',
        lengthWidth: 0.96,
        falg: true,
        height: '',
        total: ''
      }
    ]
  },
  {
    productName: 'OF330',
    packingMethods: [
      {
        packingMethod: '折叠',
        packingId: 'folding',
        volume: 0.437
      },
      {
        packingMethod: '展开箱',
        packingId: 'expand_box',
        volume: 1.67
      }
    ]
  },
  {
    productName: 'OF1040蓝箱',
    packingMethods: [
      {
        packingMethod: '折叠',
        packingId: 'folding',
        volume: 0.42
      },
      {
        packingMethod: '展开箱',
        packingId: 'expand_box',
        volume: 1.41
      }
    ]
  },
  {
    productName: 'IF1040',
    packingMethods: [
      {
        packingMethod: '折叠',
        packingId: 'folding',
        volume: 0.384
      },
      {
        packingMethod: '展开箱',
        packingId: 'expand_box',
        volume: 1.35
      }
    ]
  }
];

export const getContractType = (val: number | string) => CONTRACT_TYPE_MAP[val as keyof typeof CONTRACT_TYPE_MAP] || '';
export const getSettlementNode = (val: number | string) =>
  SETTLEMENT_NODE_MAP[val as keyof typeof SETTLEMENT_NODE_MAP] || '';
export const getReturnType = (val: number | string) => RETURN_TYPE_MAP[val as keyof typeof RETURN_TYPE_MAP] || '';
