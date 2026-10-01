package com.freedomfighter.readersnight

import android.content.Context

/** What the filter does, in a few words: "grayscale · warm tint · dimmed". */
fun summary(context: Context, o: Options): String {
    if (o.nothing) return context.getString(R.string.detail_nothing)
    return listOfNotNull(
        if (o.gray) context.getString(R.string.part_gray) else null,
        if (o.warm) context.getString(R.string.part_warm) else null,
        if (o.dim > 0) context.getString(R.string.part_dim) else null
    ).joinToString(" · ")
}
