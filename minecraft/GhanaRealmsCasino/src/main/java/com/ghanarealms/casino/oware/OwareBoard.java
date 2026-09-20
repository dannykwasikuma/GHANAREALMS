package com.ghanarealms.casino.oware;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * A standard 2x6-pit Oware board (4 seeds per pit to start, no pit-6 store
 * variant complexity - captures are collected directly into each player's
 * score). This is an original implementation of Oware's public-domain rules
 * (the game itself is centuries-old traditional Ghanaian/West African
 * mancala, not anyone's proprietary code); nothing here is derived from any
 * of the audited repositories, none of which had this game at all.
 *
 * Board layout (indices 0-11), player A owns 0-5, player B owns 6-11,
 * sowing moves counter-clockwise (increasing index, wrapping at 12):
 *
 *   11 10  9  8  7  6   <- player B row (played right-to-left visually)
 *    0  1  2  3  4  5   <- player A row
 */
public class OwareBoard {

    private final int[] pits = new int[12];
    private int scoreA = 0;
    private int scoreB = 0;
    private boolean turnIsA = true;

    public final UUID playerA;
    public final UUID playerB;
    public final double wager;

    public OwareBoard(UUID playerA, UUID playerB, double wager) {
        this.playerA = playerA;
        this.playerB = playerB;
        this.wager = wager;
        for (int i = 0; i < 12; i++) pits[i] = 4;
    }

    public boolean isPlayerATurn() {
        return turnIsA;
    }

    public UUID currentPlayer() {
        return turnIsA ? playerA : playerB;
    }

    public int[] getPits() {
        return pits.clone();
    }

    public int getScoreA() {
        return scoreA;
    }

    public int getScoreB() {
        return scoreB;
    }

    /** pitIndex is 0-5 for the CURRENT player's own row (translated internally
     *  to the real board index). Returns null on success, or an error reason. */
    public String play(boolean asPlayerA, int localPitIndex) {
        if (asPlayerA != turnIsA) return "NOT_YOUR_TURN";
        if (localPitIndex < 0 || localPitIndex > 5) return "INVALID_PIT";

        int boardIndex = turnIsA ? localPitIndex : (11 - localPitIndex);
        int seeds = pits[boardIndex];
        if (seeds == 0) return "EMPTY_PIT";

        pits[boardIndex] = 0;
        int idx = boardIndex;
        for (int s = 0; s < seeds; s++) {
            idx = (idx + 1) % 12;
            // Standard Oware rule: never sow into the pit you started from on a
            // full lap (only matters when seeds > 11; harmless to check always)
            if (idx == boardIndex) {
                idx = (idx + 1) % 12;
            }
            pits[idx]++;
        }

        // Capture rule: last seed landed in an OPPONENT pit and that pit now
        // has 2 or 3 seeds -> capture it, and keep capturing backwards while
        // the same condition holds on consecutive opponent pits.
        boolean landedInOpponentRow = turnIsA ? (idx >= 6 && idx <= 11) : (idx >= 0 && idx <= 5);
        if (landedInOpponentRow) {
            int captureIdx = idx;
            while (captureIdx >= 0 && captureIdx <= 11
                    && (turnIsA ? (captureIdx >= 6 && captureIdx <= 11) : (captureIdx >= 0 && captureIdx <= 5))
                    && (pits[captureIdx] == 2 || pits[captureIdx] == 3)) {
                if (turnIsA) scoreA += pits[captureIdx]; else scoreB += pits[captureIdx];
                pits[captureIdx] = 0;
                captureIdx--;
            }
        }

        turnIsA = !turnIsA;
        return null;
    }

    /** Game ends when a player's whole row is empty (their opponent's remaining
     *  seeds are all swept to them) or a player reaches >24 (majority of 48). */
    public boolean isOver() {
        if (scoreA > 24 || scoreB > 24) return true;
        boolean aEmpty = true, bEmpty = true;
        for (int i = 0; i <= 5; i++) if (pits[i] != 0) aEmpty = false;
        for (int i = 6; i <= 11; i++) if (pits[i] != 0) bEmpty = false;
        return aEmpty || bEmpty;
    }

    public void sweepRemaining() {
        for (int i = 0; i <= 5; i++) { scoreA += pits[i]; pits[i] = 0; }
        for (int i = 6; i <= 11; i++) { scoreB += pits[i]; pits[i] = 0; }
    }

    public UUID winner() {
        if (scoreA == scoreB) return null; // draw
        return scoreA > scoreB ? playerA : playerB;
    }
}
