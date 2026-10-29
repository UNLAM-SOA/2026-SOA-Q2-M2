module.exports = {
  uiPort: 1880,
  flowFile: 'flows.json',
  credentialSecret: process.env.NODE_RED_CREDENTIAL_SECRET,
  functionGlobalContext: {},
  contextStorage: {default: {module: 'memory'}},
  mysqlReconnectTime: 2000,
  mysqlConnectionLimit: 5,
  logging: {console: {level: 'info', metrics: false, audit: false}},
  editorTheme: {projects: {enabled: false}}
};
