package com.example.rummikubsolver.vision;

import com.example.rummikubsolver.Tile;

import java.util.ArrayList;
import java.util.List;

/**
 * turns Tile objects into the short tile code strings we send to and from the server
 * the format has to match server/src/models/HistoryEntry.js exactly
 * a normal tile is a color letter then a number like R5 or K12
 * the colors are R red B blue K black Y yellow a
 */
public final class TileCodeFormat {

    // private so nobody can do new TileCodeFormat
    private TileCodeFormat() {}

    /*
     * turns one tile into its code string
     * a joker becomes JOKER and a normal tile becomes its color letter + its number like R5
     */
    public static String toCode(Tile t) {
        if (t.isJoker()) return "JOKER";
        return colorLetter(t.getColor()) + t.getValue();
    }

    /*
     * turns a whole list of tiles into a list of code strings
     */
    public static List<String> toCodes(List<Tile> tiles) {
        List<String> codes = new ArrayList<>(tiles.size());
        for (Tile t : tiles) {
            codes.add(toCode(t));
        }
        return codes;
    }

    /*
     * turns a code string back into a Tile
     */
    public static Tile fromCode(String code, int id) {
        if ("JOKER".equals(code)) {
            return new Tile(id);
        }
        Tile.Color color = colorFor(code.charAt(0));
        int value = Integer.parseInt(code.substring(1));
        return new Tile(id, value, color);
    }
    /*
     * gives the single letter for a color R B K Y
     */
    private static String colorLetter(Tile.Color color) {
        switch (color) {
            case RED:    return "R";
            case BLUE:   return "B";
            case BLACK:  return "K";
            case YELLOW: return "Y";
            default: throw new IllegalArgumentException("Unknown color: " + color);
        }
    }

    /*
     * the opposite of colorLetter takes a letter and gives back the color
     * throws if the letter isnt one of R B K Y
     */
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
