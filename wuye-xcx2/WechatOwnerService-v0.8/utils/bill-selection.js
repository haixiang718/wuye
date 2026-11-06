'use strict';
const validId=value=>typeof value==='string' && /^[A-Za-z0-9_-]{1,30}$/.test(value);
function cents(value) {
  if(typeof value!=='string' && typeof value!=='number')return null;
  const text=String(value);
  if(!/^(?:0|[1-9]\d*)(?:\.\d{1,2})?$/.test(text))return null;
  const parts=text.split('.'),amount=Number(parts[0])*100+Number((parts[1]||'').padEnd(2,'0'));
  return Number.isSafeInteger(amount) && amount>0 && amount<=999999999 ? amount : null;
}
function eligible(fee) {
  return !!fee && fee.billSnapshot===true && ['oweId','billId','feeId'].every(key=>validId(fee[key])) && cents(fee.feePrice==null?fee.amount:fee.feePrice)!==null;
}
function ids(fees) {return [...new Set((Array.isArray(fees)?fees:[]).filter(eligible).map(fee=>fee.oweId))];}
function normalize(value) {
  if(!Array.isArray(value) || value.length>100 || !value.every(validId) || new Set(value).size!==value.length)throw new Error('账单选择无效，请重新勾选');
  return value.slice();
}
function resolve(fees,value) {
  const selected=normalize(value),available=new Set(ids(fees));
  if(selected.some(id=>!available.has(id)))throw new Error('部分账单已变化，请重新勾选后缴费');
  return selected;
}
function total(fees,value) {
  const selected=new Set(resolve(fees,value));let sum=0;
  const seen=new Set();
  (fees||[]).filter(eligible).forEach(fee=>{if(selected.has(fee.oweId) && !seen.has(fee.oweId)){sum+=cents(fee.feePrice==null?fee.amount:fee.feePrice);seen.add(fee.oweId);}});
  return (sum/100).toFixed(2);
}
function methods(field) {
  return {
    isSelectable:eligible,
    isSelected(fee){return eligible(fee) && this.selectedOweIds.includes(fee.oweId);},
    allSelected(){const available=ids(this[field]);return available.length>0 && available.length===this.selectedOweIds.length;},
    resetSelection(value=[]){this.selectedOweIds=resolve(this[field],value);this.receivableAmount=total(this[field],this.selectedOweIds);this.selectionError='';},
    toggleFee(fee){
      if(this.paymentSubmitting || !eligible(fee))return;
      const next=this.isSelected(fee)?this.selectedOweIds.filter(id=>id!==fee.oweId):this.selectedOweIds.concat(fee.oweId);
      this.resetSelection(next);
    },
    toggleAll(){if(!this.paymentSubmitting)this.resetSelection(this.allSelected()?[]:ids(this[field]));},
    hasSelection(){try{return resolve(this[field],this.selectedOweIds).length>0;}catch(_){return false;}}
  };
}
module.exports={cents,eligible,ids,normalize,resolve,total,methods,parse:value=>normalize(JSON.parse(value))};
