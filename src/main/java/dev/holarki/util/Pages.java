/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.util;

import java.util.List;
import java.util.function.Function;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/**
 * Tiny pagination helper for chat output.
 *
 * <p>Usage:
 *
 * <pre>
 * Pages.render(src, rows, page, 10, row -> Text.literal(formatRow(row)));
 * </pre>
 */
public final class Pages {
  private Pages() {}

  /**
   * Render a paginated list to chat.
   *
   * @param <T> item type
   * @param src command source to send messages to
   * @param items list of items to display
   * @param page 1-based page number
   * @param pageSize items per page (>=1)
   * @param renderLine maps an item to a {@link net.minecraft.text.Text} line
   * @return {@code 1} on success
   */
  public static <T> int render(
      ServerCommandSource src,
      List<T> items,
      int page,
      int pageSize,
      Function<T, Text> renderLine) {

    if (pageSize <= 0) pageSize = 10;
    if (page <= 0) page = 1;

    final int total = items.size();
    final int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
    if (page > pages) page = pages;

    final int from = (page - 1) * pageSize;
    final int to = Math.min(total, from + pageSize);

    final int fPage = page;
    final int fPages = pages;
    final int fFrom = from;
    final int fTo = to;
    final int fTotal = total;

    src.sendFeedback(
        () ->
            Text.literal(
                "— page "
                    + fPage
                    + "/"
                    + fPages
                    + " — showing "
                    + (fFrom + 1)
                    + "-"
                    + fTo
                    + " of "
                    + fTotal),
        false);

    for (int i = from; i < to; i++) {
      final T item = items.get(i); // capture as effectively final for the lambda
      src.sendFeedback(() -> renderLine.apply(item), false);
    }
    return 1;
  }
}
