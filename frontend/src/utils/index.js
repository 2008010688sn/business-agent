export function checkDownloadExcelFile() {
  return true;
}

export function downloadExcelFile() {
  return Promise.resolve();
}

export function encryptPassword(password) {
  return password;
}

export function maskedMobile(mobile) {
  return mobile || '';
}
