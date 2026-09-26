package com.gamingmesh.jobs.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import com.gamingmesh.jobs.Jobs;
import com.gamingmesh.jobs.container.CurrencyType;
import com.gamingmesh.jobs.container.Job;
import com.gamingmesh.jobs.container.JobsPlayer;
import com.gamingmesh.jobs.container.Schedule;

import net.Zrips.CMILib.Colors.CMIChatColor;
import net.Zrips.CMILib.Time.CMITimeManager;
import net.Zrips.CMILib.Version.Version;
import net.Zrips.CMILib.Version.Schedulers.CMIScheduler;
import net.Zrips.CMILib.Version.Schedulers.CMITask;

/**
 * One shared bossbar per job that has an active boost, shown to the players in that job (or to everyone).
 * The text, colour, style and who sees it come from the BoostBar section of generalConfig.yml.
 */
public class BoostBarManager {

    private final Jobs plugin;
    private CMITask task;
    private final Map<Job, BossBar> bars = new HashMap<>();
    // longest remaining time seen for a job's boost, so the bar can count down from full
    private final Map<Job, Long> totals = new HashMap<>();

    public BoostBarManager(Jobs plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        if (!Jobs.getGCManager().BoostBarEnabled || Version.getCurrent().isLower(Version.v1_9_R1))
            return;
        task = CMIScheduler.scheduleSyncRepeatingTask(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null)
            task.cancel();
        task = null;
        bars.values().forEach(BossBar::removeAll);
        bars.clear();
        totals.clear();
    }

    private boolean tick() {
        GeneralConfigManager gc = Jobs.getGCManager();
        List<Job> active = new ArrayList<>();

        for (Job job : Jobs.getJobs()) {
            List<String> parts = new ArrayList<>();
            long end = Long.MAX_VALUE;

            for (CurrencyType type : CurrencyType.values()) {
                if (!type.isEnabled())
                    continue;
                double boost = job.getBoost().get(type);
                if (boost <= 0)
                    continue;
                parts.add(gc.BoostBarFormat
                    .replace("%currency%", Jobs.getLanguage().getMessage("general.info.paymentType." + type.toString()))
                    .replace("%multiplier%", trim(1 + boost))
                    .replace("%percent%", trim(boost * 100)));
                Long time = job.getBoost().getTime(type);
                if (time != null)
                    end = Math.min(end, time);
            }

            if (parts.isEmpty()) {
                remove(job);
                continue;
            }

            if (end == Long.MAX_VALUE)
                end = scheduleEnd(job);

            long left = end == Long.MAX_VALUE ? -1 : Math.max(0, end - System.currentTimeMillis());
            String time = left < 0 ? gc.BoostBarForever : CMITimeManager.to24hourShort(left).trim();
            String title = CMIChatColor.translate(gc.BoostBarTitle
                .replace("%job%", job.getDisplayName())
                .replace("%boosts%", String.join(gc.BoostBarSeparator, parts))
                .replace("%time%", time));

            BossBar bar = bars.computeIfAbsent(job, j -> Bukkit.createBossBar("", gc.BoostBarColor, gc.BoostBarStyle));
            bar.setTitle(title);

            double progress = 1D;
            if (gc.BoostBarCountdown && left >= 0) {
                long total = totals.merge(job, left, Math::max);
                progress = total <= 0 ? 0D : Math.min(1D, (double) left / total);
            }
            bar.setProgress(progress);

            for (Player player : Bukkit.getOnlinePlayers()) {
                boolean show = gc.BoostBarShowAll;
                if (!show) {
                    JobsPlayer jp = Jobs.getPlayerManager().getJobsPlayer(player);
                    show = jp != null && jp.isInJob(job);
                }
                if (show)
                    bar.addPlayer(player);
                else
                    bar.removePlayer(player);
            }
            active.add(job);
        }

        bars.keySet().removeIf(job -> {
            if (active.contains(job))
                return false;
            remove(job);
            return false;
        });
        return true;
    }

    private void remove(Job job) {
        BossBar bar = bars.remove(job);
        if (bar != null)
            bar.removeAll();
        totals.remove(job);
    }

    // The boosts a schedule gives have no timer of their own, so they end when the schedule does
    private static long scheduleEnd(Job job) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        int hms = now.get(java.util.Calendar.HOUR_OF_DAY) * 10000 + now.get(java.util.Calendar.MINUTE) * 100 + now.get(java.util.Calendar.SECOND);
        long nowSeconds = seconds(hms);

        long end = Long.MAX_VALUE;
        for (Schedule one : ScheduleManager.BOOSTSCHEDULE) {
            if (!one.isStarted() || !one.getJobs().contains(job))
                continue;
            int until = one.isNextDay() && hms < one.getNextUntil() ? one.getNextUntil() : one.getUntil();
            end = Math.min(end, System.currentTimeMillis() + (seconds(until) - nowSeconds) * 1000L);
        }
        return end;
    }

    private static long seconds(int hms) {
        return (hms / 10000) * 3600L + (hms / 100 % 100) * 60L + hms % 100;
    }

    private static String trim(double value) {
        return value % 1 == 0 ? String.valueOf((long) value) : String.format("%.2f", value).replaceAll("0+$", "").replaceAll("[.]$", "");
    }
}
