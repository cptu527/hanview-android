package com.hanview.translate;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.List;

/** Places measured translation paragraphs near their source without covering another paragraph. */
final class PatchPlacement {
    static Rect find(int x, int y, int width, int height, int screenWidth, int screenHeight,
                     int gap, List<Rect> occupied) {
        if (width <= 0 || height <= 0 || width > screenWidth || height > screenHeight) return null;
        int preferredX = Math.max(0, Math.min(x, screenWidth - width));
        int preferredY = Math.max(0, Math.min(y, screenHeight - height));
        List<Integer> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        xs.add(preferredX); xs.add(0); xs.add(screenWidth - width);
        ys.add(preferredY); ys.add(0); ys.add(screenHeight - height);
        for (Rect rect : occupied) {
            xs.add(rect.right + gap); xs.add(rect.left - width - gap);
            ys.add(rect.bottom + gap); ys.add(rect.top - height - gap);
        }
        Rect best = null;
        long bestDistance = Long.MAX_VALUE;
        for (int left : xs) for (int top : ys) {
            if (left < 0 || top < 0 || left + width > screenWidth || top + height > screenHeight) continue;
            Rect candidate = new Rect(left, top, left + width, top + height);
            boolean collision = false;
            for (Rect rect : occupied) {
                if (candidate.left < rect.right + gap && candidate.right + gap > rect.left
                        && candidate.top < rect.bottom + gap && candidate.bottom + gap > rect.top) {
                    collision = true; break;
                }
            }
            long dx = left - preferredX, dy = top - preferredY;
            long distance = dx * dx + dy * dy;
            if (!collision && distance < bestDistance) { best = candidate; bestDistance = distance; }
        }
        return best;
    }
}
