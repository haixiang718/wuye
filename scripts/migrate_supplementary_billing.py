"""Add explicit supplementary-bill ancestry to the dedicated local TT database."""
import hashlib,json,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
import runtime_ops as ops

def migrate(database='TT'):
    ops.validate_source(database)
    cols=ops.mysql_query("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='"+database+"' AND TABLE_NAME='bill' AND COLUMN_NAME<>'base_bill_id' ORDER BY ORDINAL_POSITION").splitlines()
    if not cols or any(not name.replace('_','').isalnum() for name in cols):raise RuntimeError('Unexpected bill schema')
    projection=','.join('`'+name+'`' for name in cols)
    def digest():return hashlib.sha256(ops.mysql_query('SELECT '+projection+' FROM bill ORDER BY bill_id',database).encode()).hexdigest()
    before=digest()
    found=ops.mysql_query("SELECT CONCAT(COLUMN_TYPE,':',IS_NULLABLE) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='"+database+"' AND TABLE_NAME='bill' AND COLUMN_NAME='base_bill_id'")
    if found and found!='varchar(30):YES':raise RuntimeError('Existing parent column is incompatible')
    if not found:ops.mysql_query('ALTER TABLE bill ADD COLUMN base_bill_id varchar(30) NULL DEFAULT NULL COMMENT \'Original bill for a supplement\'',database)
    if digest()!=before:raise RuntimeError('Original bill columns changed; preserve evidence')
    return {'applied':not bool(found),'column':'bill.base_bill_id','originalBillColumnsUnchanged':True}

if __name__=='__main__':print(json.dumps(migrate(),indent=2))
