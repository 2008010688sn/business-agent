import { defineConfig } from '@soybeanjs/eslint-config';

export default defineConfig(
  { vue: true, unocss: true },
  {
    rules: {
      'vue/multi-word-component-names': [
        'warn',
        {
          ignores: ['index', 'App', 'Register', '[id]', '[url]']
        }
      ],
      'vue/component-name-in-template-casing': [
        'warn',
        'PascalCase',
        {
          registeredComponentsOnly: false,
          ignores: ['/^icon-/']
        }
      ],

      'vue/block-order': [
        'error',
        {
          order: [['template', 'script'], 'style']
        }
      ],
      'vue/attribute-hyphenation': 'off',
      'vue/v-on-event-hyphenation': 'off',
      'vue/html-comment-content-newline': 'off',
      'vue/require-prop-types': 'off',
      'vue/no-mutating-props': [
        'error',
        {
          shallowOnly: false
        }
      ],
      'vue/no-static-inline-styles': 'off',
      'vue/multi-word-component-names': 'off',
      'vue/no-unused-refs': 'off',
      'vue/require-prop-types': 'off',
      'vue/no-mutating-props': 'off',
      'import/order': 'off',
      '@typescript-eslint/no-unused-vars': 'off',
      '@typescript-eslint/no-use-before-define': 'off',
      '@typescript-eslint/no-shadow': 'off',
      '@typescript-eslint/no-unused-expressions': 'off',
      'no-underscore-dangle': 'off',
      'no-console': 'off',
      'no-nested-ternary': 'off',
      'no-param-reassign': 'off',
      'consistent-return': 'off',
      'func-names': 'off',
      'default-param-last': 'off',
      'no-bitwise': 'off',
      'prefer-promise-reject-errors': 'off',
      'sort-imports': 'off',
      'array-callback-return': 'off',
      'prefer-regex-literals': 'off',
      'no-useless-escape': 'off',
      'prefer-const': 'off',
      'class-methods-use-this': 'off',
      radix: 'off',
      'no-multi-assign': 'off',
      'no-warning-comments': 'off',
      'vue/no-template-target-blank': 'off',
      'vue/no-unused-properties': 'off',
      'vue/no-deprecated-v-on-native-modifier': 'off',

      // 'prettier/prettier': [
      //   'error',
      //   {
      //     endOfLine: 'auto',
      //     trailingComma: 'es5'
      //   }
      // ],
      // endOfLine: 'auto',
      'unocss/order-attributify': 'off',
      complexity: ['error', { max: 100 }],
      'no-lonely-if': 'off',
      'no-continue': 'off',
      'guard-for-in': 0,
      'no-plusplus': 0,
      'max-depth': [0, 5],
      'max-params': ['error', 5],
      eqeqeq: 'off'
    }
  }
);
