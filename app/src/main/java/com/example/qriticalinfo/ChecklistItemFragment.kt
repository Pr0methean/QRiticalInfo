package com.example.qriticalinfo

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import kotlinx.android.synthetic.main.fragment_checklist_item.*
import kotlinx.android.synthetic.main.fragment_checklist_item.view.*
import kotlinx.android.synthetic.main.fragment_checklist_item.view.checkmark
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_NAME = "nameRes"
private const val ARG_CHECKED = "checked"

/**
 * A simple [Fragment] subclass.
 * Activities that contain this fragment must implement the
 * [ChecklistItemFragment.OnFragmentInteractionListener] interface
 * to handle interaction events.
 * Use the [ChecklistItemFragment.newInstance] factory method to
 * create an instance of this fragment.
 *
 */
class ChecklistItemFragment : Fragment() {
    private val _enabled = AtomicBoolean(false)
    public var enabled : Boolean
        get() = _enabled.get()
        set(value) {
            if (_enabled.getAndSet(value) != value) {
                view?.isEnabled = value
            }
        }
    private val _onClickListener: AtomicReference<View.OnClickListener?> = AtomicReference(null)
    public var onClickListener : View.OnClickListener?
        get() = _onClickListener.get()
        set(value) {
            if (_onClickListener.getAndSet(value) != value) {
                view?.setOnClickListener(value)
            }
        }
    // TODO: Rename and change types of parameters
    private val _checked = AtomicBoolean(false)
    public var checked : Boolean
        get() = _checked.get()
        set(value) {
            if (_checked.getAndSet(value) != value) {
                view?.checkmark?.setImageResource(TODO()) // TODO
                view?.checkmark?.contentDescription = getString(if (value) R.string.abc_action_mode_done
                    else R.string.todo)
            }
        }
    private val _nameRes = AtomicInteger(R.string.loading)
    public var nameRes : Int
        get() = _nameRes.get()
        set(value) {
            if (_nameRes.getAndSet(value) != value) {
                view?.label?.setText(value)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            checked = it.getBoolean(ARG_CHECKED)
            nameRes = it.getInt(ARG_NAME, R.string.loading)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val out = inflater.inflate(R.layout.fragment_checklist_item, container, false)

        // Run the setters again to update the contents of the view
        onClickListener = onClickListener
        checked = checked
        nameRes = nameRes
        return out
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is View.OnClickListener) {
            onClickListener = context
        } else {
            throw RuntimeException(context.toString() + " must implement View.OnClickListener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        onClickListener = null
    }

    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param checked True if initially complete.
         * @param name Label for the checklist item.
         * @return A new instance of fragment ChecklistItemFragment.
         */
        @JvmStatic
        fun newInstance(checked: Boolean, name: String) =
            ChecklistItemFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_CHECKED, checked)
                    putString(ARG_NAME, name)
                }
            }
    }
}
