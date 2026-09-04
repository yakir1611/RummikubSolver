package com.example.rummikubsolver.vision;

import com.example.rummikubsolver.Tile;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts between Tile objects and the compact tile-code strings sent to/from
 * the server (see server/src/models/HistoryEntry.js for the format this must
 * match exactly): a numbered tile is <ColorLetter><Value>, where ColorLetter
 * is R=Red, B=Blue, K=Black, Y=Yellow (K for Black, since B is already taken
 * by Blue), and Value is 1-13, e.g. "R5", "K12". A joker is exactly "JOKER".
 */
public final class TileCodeFormat {

    private TileCodeFormat() {}

    public static String toCode(Tile t) {
        if (t.isJoker()) return "JOKER";
        return colorLetter(t.getColor()) + t.getValue();
    }

    public static List<String> toCodes(List<Tile> tiles) {
        List<String> codes = new ArrayList<>(tiles.size());
        for (Tile t : tiles) {
            codes.add(toCode(t));
        }
        return codes;
    }

    public static Tile fromCode(String code, int id) {
        if ("JOKER".equals(code)) {
            return new Tile(id);
        }
        Tile.Color color = colorFor(code.charAt(0));
        int value = Integer.parseInt(code.substring(1));
        return new Tile(id, value, color);
    }

    private static String colorLetter(Tile.Color color) {
        switch (color) {
            case RED:    return "R";
            case BLUE:   return "B";
            case BLACK:  return "K";
            case YELLOW: return "Y";
            default: throw new IllegalArgumentException("Unknown color: " + color);
        }
    }

    private static Tile.Color colorFor(char letter) {
        switch (letter) {
            case 'R': return Tile.Color.RED;
            case 'B': return Tile.Color.BLUE;
            case 'K': return Tile.Color.BLACK;
            case 'Y': return Tile.Color.YELLOW;
            default: throw new IllegalArgumentException("Unknown color letter: " + letter);
        }
    }
}
