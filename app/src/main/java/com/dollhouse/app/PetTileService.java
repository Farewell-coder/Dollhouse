package com.dollhouse.app;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 【职责】快捷设置磁贴：状态跟随桌宠是否在跑，点一下开、再点一下关。
 *
 * 【入口】用户从系统快捷设置面板添加；也可由设置页「权限」卡片调系统 API 请求添加。
 *
 * 【交互】只读运行状态（PetToggle.isRunning），动作交给 PetToggle；不直接碰 PetService。
 *
 * 【坑】清单必须声明 BIND_QUICK_SETTINGS_TILE 权限且 exported="true"，否则系统绑不上；
 *        onTileAdded / onTileRemoved 是唯一能准确感知「磁贴是否已被添加」的回调，
 *        设置页那个开关的显示状态就靠这两个回调回写 PetPrefs.tileAdded。
 */
public class PetTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        PetPrefs.setTileAdded(this, true);
        refresh();
    }

    @Override
    public void onTileRemoved() {
        super.onTileRemoved();
        PetPrefs.setTileAdded(this, false);
    }

    @Override
    public void onClick() {
        super.onClick();
        // 锁屏状态下不能直接动悬浮窗，等用户解锁后再执行。
        if (isLocked()) {
            unlockAndRun(new Runnable() {
                @Override
                public void run() {
                    PetToggle.toggle(PetTileService.this);
                    refresh();
                }
            });
            return;
        }
        PetToggle.toggle(this);
        refresh();
    }

    /** 把当前启停状态画进磁贴。 */
    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setState(PetToggle.isRunning(this) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
