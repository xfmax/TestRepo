package com.gotokeep.keep.pb.post.main.mvp.presenter

import android.app.Activity
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.dreamtobe.kpswitch.util.KeyboardUtil
import com.alexvasilkov.gestures.transition.tracker.SimpleTracker
import com.gotokeep.keep.analytics.AnalyticsAPI
import com.gotokeep.keep.common.extensions.activityViewModels
import com.gotokeep.keep.common.extensions.dp
import com.gotokeep.keep.common.extensions.orFalse
import com.gotokeep.keep.common.extensions.orTrue
import com.gotokeep.keep.common.utils.ActivityUtils
import com.gotokeep.keep.common.utils.RR
import com.gotokeep.keep.common.utils.ToastUtils
import com.gotokeep.keep.commonui.framework.mvp.BasePresenter
import com.gotokeep.keep.data.model.social.post.EntryPostPicPayload
import com.gotokeep.keep.pb.R
import com.gotokeep.keep.pb.edit.image.utils.exchangeListPosition
import com.gotokeep.keep.pb.post.main.activity.EntryPostPicListBottomActivity
import com.gotokeep.keep.pb.post.main.adapter.PictureAdapter
import com.gotokeep.keep.pb.post.main.fragment.EntryPostFragment
import com.gotokeep.keep.pb.post.main.listener.EntryPostPositionTrackListener
import com.gotokeep.keep.pb.post.main.listener.EntryPostRouteListener
import com.gotokeep.keep.pb.post.main.listener.PictureActionListener
import com.gotokeep.keep.pb.post.main.listener.PictureItemClickListener
import com.gotokeep.keep.pb.post.main.mvp.model.EntryPostPictureModel
import com.gotokeep.keep.pb.post.main.mvp.model.PictureItemModel
import com.gotokeep.keep.pb.post.main.mvp.view.EntryPostPictureView
import com.gotokeep.keep.pb.post.main.mvp.view.PostEditImageView
import com.gotokeep.keep.pb.post.main.utils.ACTION_TYPE_CONTENT
import com.gotokeep.keep.pb.post.main.utils.ACTION_TYPE_PICTURE
import com.gotokeep.keep.pb.post.main.utils.MAX_ALBUM_ALL_IMAGES_COUNT
import com.gotokeep.keep.pb.post.main.utils.MAX_ALBUM_IMAGES_COUNT_B
import com.gotokeep.keep.pb.post.main.utils.MAX_IMAGES_COUNT
import com.gotokeep.keep.pb.post.main.utils.REQ_CODE_SHOW_PICTURE
import com.gotokeep.keep.pb.post.main.utils.diffAndClearTempFile
import com.gotokeep.keep.pb.post.main.utils.getSafePhotoList
import com.gotokeep.keep.pb.post.main.utils.trackPostActionClick
import com.gotokeep.keep.pb.post.main.utils.trackPostContent
import com.gotokeep.keep.pb.post.main.viewmodel.EntryPostViewModel
import com.gotokeep.keep.pb.widget.EntryPostPictureItemDecoration
import com.gotokeep.keep.su.api.bean.route.SuGalleryRouteParam
import com.gotokeep.keep.su_core.gallery.GalleryView
import java.util.Collections
import kotlin.math.max
import kotlin.math.min
import kotlinx.android.synthetic.main.su_view_entry_post_picture.view.recyclerView

/**
 * 新版发布器图片处理
 *
 * @author duanlingyu
 */
class EntryPostPicturePresenterV2(recyclerView: EntryPostPictureView):
    BasePresenter<EntryPostPictureView, EntryPostPictureModel?>(recyclerView),
    DefaultLifecycleObserver {

    private val pictureAction by lazy { EntryPostViewModel.getActionImpl(view, PictureActionListener::class.java) }
    private val routeAction by lazy { EntryPostViewModel.getActionImpl(view, EntryPostRouteListener::class.java) }
    private val viewModel by view.activityViewModels<EntryPostViewModel>()

    /**
     * 图片或视频的本地路径
     */
    private var imagePathList = arrayListOf<String>()
    private var pictureAdapter: PictureAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var pictureModel: EntryPostPictureModel? = null
    private var galleryView: GalleryView? = null

    init {
        KeyboardUtil.hideKeyboard(view)
        pictureAdapter = PictureAdapter(EntryPostFragment.ENTRY_POST_FRAGMENT, object: PictureItemClickListener {

            override fun onItemClick(type: String?, position: Int) {
                when (type) {
                    TAG_ICON_CAMERA -> {
                        trackPostActionClick(ACTION_TYPE_PICTURE)
                        routeAction?.launchCapture()
                    }
                    TAG_ICON_NUMBER -> {
                        ActivityUtils.findActivity(recyclerView)?.let {
                            EntryPostPicListBottomActivity.launch(
                                it,
                                viewModel.pictureModelList,
                                viewModel.keepMusic,
                                viewModel.editData,
                                viewModel.postArgs
                            )
                        }
                    }
                    else -> {
                        checkoutPictures(position, this@EntryPostPicturePresenterV2.view.recyclerView)
                    }
                }
            }
        })

        initItemTouchHelper()

        view.recyclerView.apply {
            layoutManager = LinearLayoutManager(view.context).apply { orientation = LinearLayoutManager.HORIZONTAL }
            addItemDecoration(EntryPostPictureItemDecoration(right = 8.dp))
            adapter = pictureAdapter
        }
    }

    override fun bind(model: EntryPostPictureModel) {
        this.pictureModel = model
        if (!model.show) {
            view.visibility = View.GONE
            imagePathList.clear()
            return
        }
        viewModel.keepMusic = model.keepMusic
        view.visibility = View.VISIBLE
        view.recyclerView.visibility = View.VISIBLE

        imagePathList.clear()
        model.pathList?.let { imagePathList.addAll(it) }
        val canAddMore = imagePathList.size < (model.maxImagesCount ?: MAX_IMAGES_COUNT)
        // 组装数据
        viewModel.pictureModelList.clear()
        if (imagePathList.isNotEmpty()) {
            viewModel.pictureModelList.addAll(
                getSafePhotoList(
                    imagePathList,
                    if (model.isAlbumType) MAX_ALBUM_ALL_IMAGES_COUNT else MAX_IMAGES_COUNT
                ).mapIndexed { index, path ->
                    PictureItemModel(
                        path,
                        index,
                        if (addMorePicNumber(index, model)) TAG_ICON_NUMBER else null,
                        size = 82.dp
                    )
                })
        }
        if (!model.isAlbumType) {
            addMoreAndNotify(canAddMore)
        }
        pictureAction?.onImageUpload(imagePathList)
        pictureAdapter.setData(viewModel.pictureModelList.take(MAX_ALBUM_IMAGES_COUNT_B))
    }

    override fun onResume(owner: LifecycleOwner) {
        galleryView?.musicChoosePresenter?.onResume(owner)
    }

    override fun onPause(owner: LifecycleOwner) {
        galleryView?.musicChoosePresenter?.onPause(owner)
    }

    private fun addMorePicNumber(
        index: Int,
        model: EntryPostPictureModel
    ): Boolean {
        return index == MAX_ALBUM_IMAGES_COUNT_B - 1 && model.isAlbumType && imagePathList.size > MAX_ALBUM_IMAGES_COUNT_B
    }

    private fun initItemTouchHelper() {
        itemTouchHelper = ItemTouchHelper(object: ItemTouchHelper.Callback() {

            override fun isLongPressDragEnabled(): Boolean {
                return !viewModel.postArgs.isPhotoAlbum
            }

            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                return if (recyclerView.layoutManager is GridLayoutManager) {
                    if (viewHolder.itemView.getTag(R.id.su_entry_post_add_more_tag) == TAG_ICON_CAMERA) {
                        makeMovementFlags(0, 0)
                    } else {
                        val dragFlags =
                            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                        makeMovementFlags(dragFlags, 0)
                    }
                } else {
                    val dragFlags = ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                    makeMovementFlags(dragFlags, 0)
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (target.itemView.getTag(R.id.su_entry_post_add_more_tag) == TAG_ICON_CAMERA) {
                    // 不交换
                    return false
                }
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition
                Collections.swap(viewModel.pictureModelList, fromPosition, toPosition)
                Collections.swap(pictureAdapter.data, fromPosition, toPosition)
                (pictureAdapter.data[fromPosition] as PictureItemModel).position = fromPosition
                (pictureAdapter.data[toPosition] as PictureItemModel).position = toPosition
                pictureAdapter.notifyItemMoved(fromPosition, toPosition)
                pictureAdapter.notifyItemChanged(fromPosition, EntryPostPicPayload.UPDATE_STATUS)
                pictureAdapter.notifyItemChanged(toPosition, EntryPostPicPayload.UPDATE_STATUS)
                pictureAction?.onChildViewPositionChange(fromPosition, toPosition)
                exchangeListPosition(imagePathList, fromPosition, toPosition)
                viewModel.saveDraft()
                trackPostContent("arrange")
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // To change body of created functions use File | Settings | File Templates.
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.apply {
                        alpha = 0.5f
                        scaleX = 1.2F
                        scaleY = 1.2f
                    }
                }
                super.onSelectedChanged(viewHolder, actionState)
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                viewHolder.itemView.apply {
                    alpha = 1.0f
                    scaleX = 1.0f
                    scaleY = 1.0f
                }
                super.clearView(recyclerView, viewHolder)
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val newFloatArray = getItemLimit(viewHolder.itemView, dX, dY)
                super.onChildDraw(
                    c, recyclerView, viewHolder, newFloatArray[0], newFloatArray[1], actionState,
                    isCurrentlyActive
                )
            }
        })

        itemTouchHelper.attachToRecyclerView(view.recyclerView)
    }

    private fun getItemLimit(itemView: View, dx: Float, dy: Float): FloatArray {
        var newDy = dy
        var newDx = dx
        if (dx < 0) {
            newDx = max(dx, -itemView.left.toFloat())
        }
        if (dx > 0) {
            newDx = min(dx, (view.width - itemView.right).toFloat())
        }
        if (dy < 0) {
            newDy = max(dy, -itemView.top.toFloat())
        }
        if (dy > 0) {
            newDy = min(dy, (view.height - itemView.bottom).toFloat())
        }
        return floatArrayOf(newDx, newDy)
    }

    private fun addMoreAndNotify(canAddMore: Boolean) {
        if (canAddMore) {
            // 添加更多按钮
            viewModel.pictureModelList.add(
                PictureItemModel(
                    "",
                    viewModel.pictureModelList.size,
                    TAG_ICON_CAMERA,
                    size = 82.dp
                )
            )
        }
        pictureAdapter.notifyDataSetChanged()
    }

    /**
     * 在图片预览页查看图片
     */
    private fun checkoutPictures(index: Int, container: ViewGroup) {
        trackPostActionClick(ACTION_TYPE_CONTENT)
        pictureAction?.onImageCheckoutStart()
        val innerContainer: ViewGroup = container
        val param = SuGalleryRouteParam.Builder()
            .imagePathList(imagePathList)
            .startIndex(index)
            .editMode(true)
            .isAlbumType(pictureModel?.isAlbumType.orFalse())
            .setMusicData(pictureModel?.keepMusic)
            .requestListener(EntryPostPositionTrackListener(container, object: SimpleTracker() {
                override fun getViewAt(position: Int): View? {
                    return innerContainer.getChildAt(position)
                }
            })).build()
        galleryView = GalleryView(view.context as FragmentActivity, param).apply {
            val isShowEditButton = pictureAction?.hasPhotoEditData().orFalse() || pictureModel?.isAlbumType.orTrue()
            setFloatPanelView(
                PostEditImageView(
                    this,
                    isShowEditButton,
                    !viewModel.postArgs.isPhotoAlbum,
                    !viewModel.postArgs.isPhotoAlbum
                ).apply {
                    setOnEditClickListener { index, block ->
                        if (viewModel.isContainErrorFile(viewModel.editData)) {
                            ToastUtils.show(RR.getString(R.string.su_choose_again))
                        } else {
                            block.invoke()
                            routeAction?.openImageEditPage(index)
                        }
                    }
                    setImageDeleteListener {
                        notifyDataChange()
                    }
                })
            setOnGalleryExit { requestCode, resultCode, data ->
                if (resultCode == Activity.RESULT_OK && requestCode == REQ_CODE_SHOW_PICTURE) {
                    // 查看图片可能会删掉其中的几张，如果图片数量有变化，需要更新
                    val paths = data.getStringArrayListExtra(SuGalleryRouteParam.BUNDLE_KEY_IMAGE_PATH_LIST)
                        .orEmpty()
                    pictureAction?.onImageCheckoutEnd(paths)
                    diffAndClearTempFile(imagePathList, paths)
                }
            }
            setRequestCode(REQ_CODE_SHOW_PICTURE)
            show()
        }
        AnalyticsAPI.track("page_camera_preview", mapOf("type" to "pic"))
    }

    companion object {
        /**
         * 添加更多 view 的 type,用来区分特殊条目
         */
        const val TAG_ICON_CAMERA = "addMore"

        /**
         * 点击可以查看更多
         */
        const val TAG_ICON_NUMBER = "showMore"
    }
}
