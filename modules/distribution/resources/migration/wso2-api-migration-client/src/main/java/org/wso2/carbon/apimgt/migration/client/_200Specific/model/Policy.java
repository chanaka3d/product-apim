package org.wso2.carbon.apimgt.migration.client._200Specific.model;


public class Policy {
    private String type;
    private String name;
    private int maxCount;
    private long unitTime;
    private String billingPlan;
    private boolean stopOnQuotaReach;
    private String description;
    private byte[] customAttributes;


    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getMaxCount() {
        return maxCount;
    }

    public void setMaxCount(int maxCount) {
        this.maxCount = maxCount;
    }

    public long getUnitTime() {
        return unitTime;
    }

    public void setUnitTime(long unitTime) {
        this.unitTime = unitTime;
    }

    public String getBillingPlan() {
        return billingPlan;
    }

    public void setBillingPlan(String billingPlan) {
        this.billingPlan = billingPlan;
    }

    public boolean isStopOnQuotaReach() {
        return stopOnQuotaReach;
    }

    public void setStopOnQuotaReach(boolean stopOnQuotaReach) {
        this.stopOnQuotaReach = stopOnQuotaReach;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Policy{");
        sb.append("type='").append(type).append('\'');
        sb.append(", name='").append(name).append('\'');
        sb.append(", maxCount=").append(maxCount);
        sb.append(", unitTime=").append(unitTime);
        sb.append(", billingPlan='").append(billingPlan).append('\'');
        sb.append(", stopOnQuotaReach=").append(stopOnQuotaReach);
        sb.append('}');
        return sb.toString();
    }

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public byte[] getCustomAttributes() {
		return customAttributes;
	}

	public void setCustomAttributes(byte[] customAttributes) {
		this.customAttributes = customAttributes;
	}
}
