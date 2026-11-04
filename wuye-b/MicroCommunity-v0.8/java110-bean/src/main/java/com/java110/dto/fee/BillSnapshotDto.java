package com.java110.dto.fee;

import java.io.Serializable;
import java.util.List;

/** A fully computed bill; persisted atomically by the fee service. */
public class BillSnapshotDto implements Serializable {
    private BillDto bill;
    private List<BillOweFeeDto> details;
    private String billType;
    private String previousBillId;
    private boolean supplemental;

    public BillDto getBill() { return bill; }
    public void setBill(BillDto bill) { this.bill = bill; }
    public List<BillOweFeeDto> getDetails() { return details; }
    public void setDetails(List<BillOweFeeDto> details) { this.details = details; }
    public String getBillType() { return billType; }
    public void setBillType(String billType) { this.billType = billType; }
    public String getPreviousBillId() { return previousBillId; }
    public void setPreviousBillId(String previousBillId) { this.previousBillId = previousBillId; }
    public boolean isSupplemental() { return supplemental; }
    public void setSupplemental(boolean supplemental) { this.supplemental = supplemental; }
}
