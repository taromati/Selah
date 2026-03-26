package me.taromati.almah.web.setup.dto;

import java.util.List;

public record DoctorResult(
        List<CheckItem> items,
        int passed,
        int failed,
        int warned
) {}
