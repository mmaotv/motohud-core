package com.motohud.ble.ui.navi

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import com.motohud.ble.ui.common.MotoDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.motohud.ble.R
import com.motohud.ble.data.SavedPlaceEntity

/**
 * 添加/编辑常用地址对话框。
 * type: home / work / other；坐标默认填入当前定位（如有）。
 */
class PlaceEditDialog : DialogFragment() {

    /** 保存回调 */
    var onSave: ((name: String, lat: Double, lng: Double, type: String, remark: String) -> Unit)? = null

    private var editing: SavedPlaceEntity? = null
    private var etLatRef: TextInputEditText? = null
    private var etLngRef: TextInputEditText? = null

    /** 地图选点回来的坐标回填（DialogFragment 需要在自己的生命周期内注册） */
    private val pickMap = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val lat = r.data?.getDoubleExtra(MapPickActivity.EXTRA_LAT, 0.0) ?: 0.0
        val lng = r.data?.getDoubleExtra(MapPickActivity.EXTRA_LNG, 0.0) ?: 0.0
        if (lat != 0.0 && lng != 0.0) {
            etLatRef?.setText(lat.toString())
            etLngRef?.setText(lng.toString())
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // 从参数取（DialogFragment 重建安全）
        val editId = arguments?.getLong("id", 0L) ?: 0L
        val name0 = arguments?.getString("name").orEmpty()
        val lat0: Double = arguments?.getDouble("lat", Double.NaN) ?: Double.NaN
        val lng0: Double = arguments?.getDouble("lng", Double.NaN) ?: Double.NaN
        val type0 = arguments?.getString("type", SavedPlaceEntity.TYPE_OTHER).orEmpty()
        val remark0 = arguments?.getString("remark").orEmpty()
        editing = if (editId > 0) SavedPlaceEntity(editId, name0, lat0, lng0, type0, remark0) else null

        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_place_edit, null, false)
        val etName = view.findViewById<TextInputEditText>(R.id.et_place_name)
        val etLat = view.findViewById<TextInputEditText>(R.id.et_place_lat)
        val etLng = view.findViewById<TextInputEditText>(R.id.et_place_lng)
        val etRemark = view.findViewById<TextInputEditText>(R.id.et_place_remark)
        val ddType = view.findViewById<MaterialAutoCompleteTextView>(R.id.dd_place_type)

        val typeLabels = listOf("家", "公司", "其他")
        val typeValues = listOf(
            SavedPlaceEntity.TYPE_HOME,
            SavedPlaceEntity.TYPE_WORK,
            SavedPlaceEntity.TYPE_OTHER
        )
        ddType.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, typeLabels)
        )

        val prefill = editing
        if (prefill != null) {
            etName.setText(prefill.name)
            etLat.setText(prefill.latitude.toString())
            etLng.setText(prefill.longitude.toString())
            etRemark.setText(prefill.remark)
            ddType.setText(typeLabels[typeValues.indexOf(prefill.type).coerceAtLeast(0)], false)
        } else {
            ddType.setText(typeLabels[0], false)
            // 名称可能已由「地点卡 → 收藏」带过来，就不让用户再手打一遍
            arguments?.getString(ARG_DEFAULT_NAME)?.takeIf { it.isNotBlank() }?.let { etName.setText(it) }
            val dLat = arguments?.getDouble(ARG_DEFAULT_LAT, Double.NaN) ?: Double.NaN
            val dLng = arguments?.getDouble(ARG_DEFAULT_LNG, Double.NaN) ?: Double.NaN
            if (!dLat.isNaN() && !dLng.isNaN()) {
                etLat.setText(dLat.toString())
                etLng.setText(dLng.toString())
            } else {
                // 既没带坐标也没有定位：直接给出「在地图上选点」，别让用户面对两个空输入框
                etLat.setText("")
                etLng.setText("")
            }
        }

        etLatRef = etLat
        etLngRef = etLng
        view.findViewById<android.view.View>(R.id.btn_place_pick_map).setOnClickListener {
            val curLat = etLat.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
            val curLng = etLng.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
            pickMap.launch(MapPickActivity.intent(requireContext(), curLat, curLng))
        }

        return MotoDialog.builder(requireContext())
            .setTitle(if (editing != null) R.string.place_edit_title else R.string.place_add_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = etName.text?.toString()?.trim().orEmpty()
                val lat = etLat.text?.toString()?.trim()?.toDoubleOrNull()
                val lng = etLng.text?.toString()?.trim()?.toDoubleOrNull()
                val remark = etRemark.text?.toString()?.trim().orEmpty()
                val idx = ddType.text?.toString()?.let { typeLabels.indexOf(it) } ?: 0
                val type = typeValues.getOrElse(idx) { SavedPlaceEntity.TYPE_OTHER }
                if (name.isEmpty() || lat == null || lng == null ||
                    lat < -90 || lat > 90 || lng < -180 || lng > 180
                ) {
                    android.widget.Toast.makeText(
                        requireContext(), R.string.place_input_invalid, android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                onSave?.invoke(name, lat, lng, type, remark)
            }
            .create()
    }

    companion object {
        private const val ARG_DEFAULT_LAT = "default_lat"
        private const val ARG_DEFAULT_LNG = "default_lng"
        private const val ARG_DEFAULT_NAME = "default_name"

        fun newInstance(
            place: SavedPlaceEntity? = null,
            defaultLatLng: Pair<Double, Double>? = null,
            defaultName: String? = null
        ): PlaceEditDialog =
            PlaceEditDialog().apply {
                arguments = Bundle().apply {
                    if (place != null) {
                        putLong("id", place.id)
                        putString("name", place.name)
                        putDouble("lat", place.latitude)
                        putDouble("lng", place.longitude)
                        putString("type", place.type)
                        putString("remark", place.remark)
                    }
                    defaultLatLng?.let {
                        putDouble(ARG_DEFAULT_LAT, it.first)
                        putDouble(ARG_DEFAULT_LNG, it.second)
                    }
                    defaultName?.let { putString(ARG_DEFAULT_NAME, it) }
                }
            }
    }
}
