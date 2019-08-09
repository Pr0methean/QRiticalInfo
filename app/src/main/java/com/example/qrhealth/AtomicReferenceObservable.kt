package com.example.qrhealth

import java.util.concurrent.atomic.AtomicReference
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

open class AtomicReferenceObservable<T>(initialValue: T, val onChange: (T, T) -> Unit) : ReadWriteProperty<Any?, T> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return field.get()
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        val old = field.getAndSet(value)
        if (old !== value) {
            onChange(old, value)
        }
    }

    private val field = AtomicReference<T>(initialValue)

}