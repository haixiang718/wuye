'use strict';
// Avoid the original bin/www, which listens on every interface.
const app = require('../../admin/app');
app.listen(3000, '127.0.0.1', () => console.log('Management listener ready on loopback.'));
