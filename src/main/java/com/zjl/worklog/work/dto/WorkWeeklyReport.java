package com.zjl.worklog.work.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class WorkWeeklyReport {
    private String week;
    private String from;
    private String to;
    private String realName;
    private List<WorkWeeklyItem> done = new ArrayList<>();
    private List<WorkWeeklyItem> doing = new ArrayList<>();
    private List<WorkExpenseStat> expenses = new ArrayList<>();
    private BigDecimal expenseTotal;
    private String text;
}
