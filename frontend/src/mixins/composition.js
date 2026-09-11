import { reactive, ref } from 'vue';
import { ElLoading, dayjs } from 'element-plus';
import { useTabStore } from '@/store/modules/tab';
import { $t } from '@/locales';
const tabStore = useTabStore();

export function useCommonMixin() {
  const globalLoadingObj = ref(null);
  const loadingTimer = ref(null);
  const getTimeAll = (time, type = undefined) => {
    if (!time) {
      return '';
    }
    if (type === 'ym') {
      return dayjs(time).format('YYYY-MM');
    }
    if (type) {
      return dayjs(time).format('YYYY-MM-DD');
    }
    return dayjs(time).format('YYYY-MM-DD HH:mm:ss');
  };

  const toFixedSmart = (num, len) => {
    const strNum = String(num).indexOf('.');
    const end = strNum !== -1 && String(num).slice(Math.max(0, strNum + 1));
    if (!num) {
      return Number(num);
    }
    if (strNum <= -1 || end.length < len) {
      return Number.parseFloat(num);
    }
    return Number.parseFloat(Number(num).toFixed(len || 2));
  };
  const toThousands = num => {
    if (num === '' || num === null || num === undefined) {
      return '';
    }
    if (Number.isNaN(num)) {
      return num;
    }
    const decimal = 2;
    // eslint-disable-next-line no-param-reassign
    num = num.toString();
    const index = num.indexOf('.');
    // eslint-disable-next-line no-param-reassign
    num = index === -1 ? [num] : num.slice(0, Math.max(0, decimal + index + 1));
    return Number.parseFloat(num)
      .toFixed(decimal)
      .toString()
      .replaceAll(/\d{1,3}(?=(\d{3})+(\.\d*)?$)/g, '$&,');
  };
  let resizeListenerAdded = false;
  let cachedBili = null;
  let resizeHandler = null;

  function detectZoom() {
    let ratio = 0;
    const screen = window.screen;
    const ua = navigator.userAgent.toLowerCase();
    if (window.devicePixelRatio !== undefined) {
      ratio = window.devicePixelRatio;
      // eslint-disable-next-line no-bitwise, no-implicit-coercion
    } else if (~ua.indexOf('msie')) {
      if (screen.deviceXDPI && screen.logicalXDPI) {
        ratio = screen.deviceXDPI / screen.logicalXDPI;
      }
    } else if (window.outerWidth !== undefined && window.innerWidth !== undefined) {
      ratio = window.outerWidth / window.innerWidth;
    }
    if (ratio) {
      ratio = Math.round(ratio * 100);
    }
    return ratio / 100;
  }

  const returnMaxTable = () => {
    if (!resizeListenerAdded) {
      cachedBili = detectZoom();
      resizeHandler = () => {
        cachedBili = detectZoom();
      };
      window.addEventListener('resize', resizeHandler);
      resizeListenerAdded = true;
    }
    const bili = Math.min(cachedBili || 1, 1);
    return window.screen.height >= 1080 ? 500 * (1 + (1 - bili) * 2) : 450 * (1 + (1 - bili) * 2);
  };
  /**
   * @deprecated 废弃，后续深拷贝调用 Lodash的cloneDeep，逐步替换并移除该方法
   * @param {*} target
   * @returns
   */
  const deepClone = target => {
    // 定义一个变量
    let result;
    // 如果当前需要深拷贝的是一个对象的话
    if (typeof target === 'object') {
      // 如果是一个数组的话
      if (Array.isArray(target)) {
        result = []; // 将result赋值为一个数组，并且执行遍历
        for (const i in target) {
          // 递归克隆数组中的每一项
          result.push(deepClone(target[i]));
        }
        // 判断如果当前的值是null的话；直接赋值为null
      } else if (target === null) {
        result = null;
        // 判断如果当前的值是一个RegExp对象的话，直接赋值
      } else if (target.constructor === RegExp) {
        result = target;
      } else {
        // 否则是普通对象，直接for in循环，递归赋值对象的所有值
        result = {};
        for (const i in target) {
          result[i] = deepClone(target[i]);
        }
      }
      // 如果不是对象的话，就是基本数据类型，那么直接赋值
    } else {
      result = target;
    }
    // 返回最终结果
    return result;
  };
  const postExcelFile = (blobUrl, filename) => {
    // blobUrl 是new Blob处理后的数据, filename下载的文件名
    const eleLink = document.createElement('a');
    eleLink.download = filename;
    eleLink.style.display = 'none';
    eleLink.href = blobUrl;
    // 触发点击
    document.body.append(eleLink);
    eleLink.click();
    // 然后移除
    eleLink.remove();
  };
  const numberThousands = number => {
    return `${number}`.replaceAll(/(\d{1,3})(?=(?:\d{3})+(?:$|\.))/g, '$1,');
  };

  const getCurrentTimeZone = () => {
    const offset = -new Date().getTimezoneOffset() / 60;
    if (offset >= 0) {
      return `UTC+${offset}`;
    }
    return `UTC${offset}`;
  };

  /**
   * 时间 今天的年月日
   *
   * @param
   */
  const getToday = () => {
    return dayjs().format('YYYY-MM-DD');
  };
  /**
   * 时间 今天的年月日
   *
   * @param heaven最近的heaven天数
   */
  const getLastDays = heaven => {
    return dayjs().subtract(heaven, 'day').format('YYYY-MM-DD');
  };
  /**
   * 时间格式化
   *
   * @param {string} fmt 格式化规则，例如：'YYYY-mm-dd HH:MM:SS'
   */
  const dateFormat = (fmt, time) => {
    if (Number.isNaN(time)) {
      return '';
    }
    if (time === null || Number(time) === 0) {
      return '';
    }
    const date = new Date(time);
    let ret;
    const opt = {
      'Y+': date.getFullYear().toString(), // 年
      'm+': (date.getMonth() + 1).toString(), // 月
      'd+': date.getDate().toString(), // 日
      'H+': date.getHours().toString(), // 时
      'M+': date.getMinutes().toString(), // 分
      'S+': date.getSeconds().toString() // 秒
    };
    for (const k in opt) {
      ret = new RegExp(`(${k})`).exec(fmt);
      if (ret) {
        // eslint-disable-next-line no-param-reassign
        fmt = fmt.replace(ret[1], ret[1].length === 1 ? opt[k] : opt[k].padStart(ret[1].length, '0'));
      }
    }
    return fmt;
  };
  /**
   * 获取某个日期前一个月的
   *
   * @param {string | Date} date 时间字符串 或者 时间对象
   */
  const getPreMonthDate = date => {
    return dayjs(date).subtract(1, 'month').format('YYYY-MM-DD');
  };
  /**
   * 获取当前日期某天之后或者之前的日期
   *
   * @param {number} num 某天之前或者之后 eg：-1 1天前 1 1天后
   */
  const getAfterNumDate = (num, fmt) => {
    const time = Date.now() + num * 24 * 60 * 60 * 1000;
    return dateFormat(fmt, time);
  };

  /** 获取指定月份范围内的时间 */
  const getPastMonthsDateRange = (num = 1) => {
    const now = dayjs(); // 只调用一次 dayjs()，避免重复计算
    const startDate = now.subtract(num, 'month').add(1, 'day');
    const startTime = startDate.startOf('day').toDate(); // 直接获取当天的 00:00:00
    const endTime = now.endOf('day').toDate(); // 直接获取当天的 23:59:59
    return [startTime, endTime];
  };

  /** 获取半年内的时间 */
  const getDateArrayHalfYear = () => {
    return getPastMonthsDateRange(6);
  };

  const getDateArrayByMonth = (num = 1) => {
    const now = dayjs();
    const endTime = now.endOf('month').endOf('day').toDate();
    const startDate = now.subtract(num - 1, 'month');
    const startTime = startDate.startOf('month').startOf('day').toDate();
    return [startTime, endTime];
  };

  const getLast7Days = () => {
    return [getAfterNumDate(-6, 'YYYY-mm-dd'), getAfterNumDate(0, 'YYYY-mm-dd')];
  };

  /**
   * base64的字符串数据转Blob对象
   *
   * @param {urlData} 图片地址 图片对象
   */
  const convertBase64UrlToBlob = data => {
    const byteString = data.split(',')[0].includes('base64') ? atob(data.split(',')[1]) : unescape(data.split(',')[1]);
    const mimeString = data.split(',')[0].split(':')[1].split(';')[0];
    const ia = new Uint8Array(byteString.length);
    for (let i = 0; i < byteString.length; i += 1) {
      ia[i] = byteString.codePointAt(i);
    }
    return new Blob([ia], { type: mimeString });
  };
  /**
   * 图片压缩，默认同比例压缩
   *
   * @param {object} fileObj 图片对象 回调函数有一个参数，base64的字符串数据
   */
  /* 图片压缩方法-canvas压缩 */
  const compressUpload = (image, file, quality) => {
    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d');

    const { height } = image;
    const { width } = image;

    let targetWidth = width;
    let targetHeight = height;
    const originWidth = width;
    const originHeight = height;
    let maxWidth;
    let maxHeight;
    if (
      1 * 1024 <= Number.parseInt((file.size / 1024).toFixed(2), 10) &&
      Number.parseInt((file.size / 1024).toFixed(2), 10) <= 10 * 1024
    ) {
      maxWidth = 1600;
      maxHeight = 1600;
      targetWidth = originWidth;
      targetHeight = originHeight;
      // 图片尺寸超过的限制
      if (originWidth > maxWidth || originHeight > maxHeight) {
        if (originWidth / originHeight > maxWidth / maxHeight) {
          // 更宽，按照宽度限定尺寸
          targetWidth = maxWidth;
          targetHeight = Math.round(maxWidth * (originHeight / originWidth));
        } else {
          targetHeight = maxHeight;
          targetWidth = Math.round(maxHeight * (originWidth / originHeight));
        }
      }
    }
    if (
      10 * 1024 <= Number.parseInt((file.size / 1024).toFixed(2), 10) &&
      Number.parseInt((file.size / 1024).toFixed(2), 10) <= 50 * 1024
    ) {
      maxWidth = 1400;
      maxHeight = 1400;
      targetWidth = originWidth;
      targetHeight = originHeight;
      // 图片尺寸超过的限制
      if (originWidth > maxWidth || originHeight > maxHeight) {
        if (originWidth / originHeight > maxWidth / maxHeight) {
          // 更宽，按照宽度限定尺寸
          targetWidth = maxWidth;
          targetHeight = Math.round(maxWidth * (originHeight / originWidth));
        } else {
          targetHeight = maxHeight;
          targetWidth = Math.round(maxHeight * (originWidth / originHeight));
        }
      }
    }
    canvas.width = targetWidth;
    canvas.height = targetHeight;
    ctx.clearRect(0, 0, targetWidth, targetHeight);
    ctx.drawImage(image, 0, 0, targetWidth, targetHeight); // 绘制 canvas
    // 进行最小压缩0.1
    const compressData = canvas.toDataURL(file.type || 'image/jpeg', quality);
    // 压缩后调用方法进行base64转Blob，方法写在下边
    const blobImg = convertBase64UrlToBlob(compressData);
    return blobImg;
  };

  // 判断图片大小-进行压缩
  const beforeUpload = file => {
    return new Promise((resolve, reject) => {
      if ((file.size / 1024 / 1024).toFixed(2) < 1) {
        // 设置1M 以上进行压缩
        resolve({
          file
        });
      } else {
        const image = new Image();
        let resultBlob = '';
        image.src = URL.createObjectURL(file);
        image.addEventListener('load', () => {
          // 调用方法获取blob格式，方法写在下边
          let quality = 0.52;
          if (file.size / 1024 / 1024 > 10) {
            // 大于20M
            quality = 0.2;
          } else if (file.size / 1024 / 1024 <= 10 && file.size / 1024 / 1024 > 5) {
            // 5-20M
            quality = 0.52;
          } else {
            quality = 0.75;
          }
          resultBlob = compressUpload(image, file, quality);
          resolve(resultBlob);
        });
        image.addEventListener('error', () => {
          reject(new Error('Image loading failed'));
        });
      }
    });
  };
  /**
   * cgj
   *
   * @param {evt} 默认事件 在el-input type='number' 禁止鼠标滚动 使用方法 @wheel.native.prevent="stopScroll($event)"
   */
  const stopScroll = evt => {
    // eslint-disable-next-line no-param-reassign
    evt ||= window.event;
    if (evt.preventDefault) {
      // Firefox
      evt.preventDefault();
      evt.stopPropagation();
    } else {
      // IE
      evt.cancelBubble = true;
      evt.returnValue = false;
    }
    return false;
  };
  /** 数组对象去重 */
  const arrNoRepeat = (arr, key) => {
    if (!key) {
      return [...new Set(arr)];
    }
    const obj = {};
    const result = [];
    for (const next of arr) {
      if (!obj[next[key]]) {
        obj[next[key]] = true;
        result.push(next);
      }
    }
    // eslint-disable-next-line no-param-reassign
    arr = result;
    return arr;
  };

  const startLoading = (type, timer = 200, callback = undefined) => {
    if (type === undefined) {
      return;
    }
    // eslint-disable-next-line consistent-return
    loadingTimer.value = setTimeout(() => {
      if (type === 'global') {
        globalLoadingObj.value = null;
        globalLoadingObj.value = ElLoading.service({
          lock: true,
          text: $t('common.loading')
        });
      }
      if (Boolean(callback) && typeof callback === 'function') {
        callback();
      }
    }, timer);
  };
  /**
   * 关闭掉loading
   *
   * @param {string} type：'part'| 'global' loading类型 必传
   * @param {boolean} loading loading对象 type为'part'时，必传
   * @param {Function} callback 回调函数，非必传
   */
  const endLoading = (type, callback) => {
    clearTimeout(loadingTimer.value);
    if (type === undefined) {
      return;
    }
    if (type === 'global' && globalLoadingObj.value) {
      globalLoadingObj.value.close();
    }
    if (Boolean(callback) && typeof callback === 'function') {
      callback();
    }
  };
  const closeTab = () => {
    tabStore.removeActiveTab();
  };
  const isInteger = obj => {
    return Math.floor(obj) === obj;
  };
  const toInteger = floatNum => {
    const ret = { times: 1, num: 0 };
    if (isInteger(floatNum)) {
      ret.num = floatNum;
      return ret;
    }
    const strfi = `${floatNum}`;
    const dotPos = strfi.indexOf('.');
    const len = strfi.substr(dotPos + 1).length;
    const times = 10 ** len;
    const intNum = Number.parseInt(floatNum * times + 0.5, 10);
    ret.times = times;
    ret.num = intNum;
    return ret;
  };
  const operation = (a, b, op) => {
    const o1 = toInteger(a);
    const o2 = toInteger(b);
    const n1 = o1.num;
    const n2 = o2.num;
    const t1 = o1.times;
    const t2 = o2.times;
    const max = t1 > t2 ? t1 : t2;
    let result = null;
    switch (op) {
      case 'add':
        if (t1 === t2) {
          result = n1 + n2;
        } else if (t1 > t2) {
          result = n1 + n2 * (t1 / t2);
        } else {
          result = n1 * (t2 / t1) + n2;
        }
        return result / max;
      case 'subtract':
        if (t1 === t2) {
          result = n1 - n2;
        } else if (t1 > t2) {
          result = n1 - n2 * (t1 / t2);
        } else {
          result = n1 * (t2 / t1) - n2;
        }
        return result / max;
      case 'multiply':
        result = (n1 * n2) / (t1 * t2);
        return result;
      default:
        return result;
    }
  };

  // 加减乘除的四个函数
  const add = (a, b) => {
    return operation(a, b, 'add');
  };

  const subtract = (a, b) => {
    return operation(a, b, 'subtract');
  };

  const multiply = (a, b) => {
    return operation(a, b, 'multiply');
  };

  /**
   * 小数位数不能超过place
   * @param {*} place 小数位数
   * @returns
   */
  const decimalPlaceValidator = place => {
    return (_rule, value, callback) => {
      const regStr = place ? `^(([1-9]{1}\\d*)|(0{1}))(\\.\\d{1,${place}})?$` : '^(?:[1-9]\\d*)$';
      const decimalRegex = new RegExp(regStr);
      if (value && decimalRegex.test(value) == false) {
        callback(new Error(place ? `请输入${place}位小数` : '请输入正整数'));
      } else {
        callback();
      }
    };
  };

  /**
   * 字段校验
   * @param {*} required 可为空
   * @param {*} message 提示消息
   * @param {*} decimalPlace 最大允许小数位
   * @returns
   */
  const fieldRule = (required = true, message = '', items = [], trigger = 'blur') => {
    const itemRule = [{ required, message, trigger }];
    for (const item of items) {
      if (item.type === 'decimal') {
        itemRule.push({ validator: decimalPlaceValidator(item.value), trigger });
      } else if (item.type === 'max') {
        itemRule.push({ max: item.value, message: `不超过${item.value}字`, trigger });
      }
    }
    return itemRule;
  };

  /**
   * 今天 00:00:00 之前的日期不可选
   */
  const disabledBeforeToday = time => {
    return time.getTime() < Date.now() - 24 * 60 * 60 * 1000;
  };

  const approveStatusList = reactive([
    { id: '1', name: '审批中' },
    { id: '4', name: '已驳回' },
    { id: '6', name: '已撤回' },
    { id: '8', name: '已通过' }
  ]);

  const isNonEmptyParams = restParams => {
    return (
      Object.entries(restParams).filter(([key, value]) => {
        if (value === null || value === undefined) return false;
        if (Array.isArray(value) && value.length === 0) return false;
        if (typeof value === 'string' && value.trim() === '') return false;
        return true;
      }).length > 0
    );
  };

  return {
    getTimeAll,
    toFixedSmart,
    toThousands,
    returnMaxTable,
    deepClone,
    postExcelFile,
    numberThousands,
    arrNoRepeat,
    stopScroll,
    convertBase64UrlToBlob,
    compressUpload,
    beforeUpload,
    getLast7Days,
    getPreMonthDate,
    getLastDays,
    getToday,
    getCurrentTimeZone,
    startLoading,
    endLoading,
    getAfterNumDate,
    dateFormat,
    closeTab,
    getDateArrayHalfYear,
    getPastMonthsDateRange,
    getDateArrayByMonth,
    add,
    subtract,
    multiply,
    disabledBeforeToday,
    decimalPlaceValidator,
    fieldRule,
    approveStatusList,
    isNonEmptyParams
  };
}
