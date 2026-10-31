package com.mapcast.app

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup

/**
 * Sanal ekrana (VirtualDisplay'in Display'i) bağlanan pencere.
 * İçeriği MapView olduğu için YALNIZCA bu harita, telefon ekranı değil,
 * sanal ekrana çizilir ve oradan kodlayıcıya gider.
 */
class MapPresentation(context: Context, display: Display) : Presentation(context, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = MapView(context)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(view)
    }
}
