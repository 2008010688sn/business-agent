import { defineStore } from 'pinia';

/**
 * 创建并导出表格字段数据存储的 Vue3 store 对象
 *
 * @function
 * @returns {DictionaryStore} - 返回创建的基础列表存储对象
 */
export const tableColumns = defineStore('table-column', {
  state: () => ({
    tableColumnLit: [] as any[]
  }),
  actions: {
    /**
     * 获取指定键的值
     *
     * @function
     * @param {string} key - 需要获取的键
     * @returns {Object | null} - 返回指定键对应的值，如果找不到则返回 null
     */
    getList(key: string) {
      try {
        const item = this.tableColumnLit.find(el => el.key === key);
        return item ? item.value : null;
      } catch (e: any) {
        return null;
      }
    },

    /**
     * 设置一个键值对
     *
     * @function
     * @param {string} key - 需要设置的键
     * @param {Object} value - 需要设置的值
     */
    setList(key: string, value: any) {
      try {
        if (!key || typeof key !== 'string') {
          return;
        }
        const index = this.tableColumnLit.findIndex(item => item.key === key);
        if (index !== -1) {
          this.tableColumnLit[index] = { key, value };
        } else {
          this.tableColumnLit.push({ key, value });
        }
      } catch (e: any) {
        console.error(e);
      }
    },

    /**
     * 删除指定键值对
     *
     * @function
     * @param {string} key - 需要删除的键
     * @returns {boolean} - 返回删除操作是否成功
     */
    removeList(key: string) {
      try {
        const index = this.tableColumnLit.findIndex(item => item.key === key);
        if (index !== -1) {
          this.tableColumnLit.splice(index, 1);
          return true;
        }
      } catch (e: any) {
        return false;
      }
      return false;
    },
    clear() {
      this.tableColumnLit = [];
    }
  },
  persist: true
});
