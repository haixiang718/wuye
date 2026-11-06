'use strict';
// Display only. Payment amounts continue to come from server-side bill snapshots.
function money(value) {
  if (value == null || typeof value === 'boolean' || String(value).trim() === '') return '';
  const amount = Number(value);
  return Number.isFinite(amount) && amount >= 0 ? amount.toFixed(2) : '';
}
function date(value) {
  const match = typeof value === 'string' && value.match(/^(\d{4})([-/])(\d{2})\2(\d{2})(?:[ T]|$)/);
  return match ? [match[1],match[3],match[4]].join('-') : '—';
}
module.exports = {money,date};
