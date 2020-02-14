package com.example.qriticalinfo

import java.util.concurrent.atomic.AtomicReference
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

open class AtomicReferenceObservable<T>(initialValue: T, val onChange: (oldValue: T, newValue: T) -> Unit)
        : ReadWriteProperty<Any?, T> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return field.get()
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        val oldValue = field.getAndSet(value)
        if (oldValue !== value) {
            onChange(oldValue, value)
        }
    }

    private val field = AtomicReference<T>(initialValue)

}