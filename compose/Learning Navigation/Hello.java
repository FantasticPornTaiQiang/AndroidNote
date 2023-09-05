    public static final void HelloWorld(Composer _composer, final int changed) {
        Composer composer = _composer.startRestartGroup(1016335467);
        
        //若有变化，或者composer认为不能跳过，则不跳过，否则跳过
        if (changed != 0 || !composer.getSkipping()) {
            
            composer.startReplaceableGroup(-492369756);
            //取mutableState，即count
            Object rememberedValue = composer.rememberedValue();
            //没有则新创建
            if (rememberedValue == Composer.Companion.getEmpty()) {
                rememberedValue = SnapshotStateKt__SnapshotStateKt.mutableStateOf$default(0, null, 2, null);
                composer.updateRememberedValue(rememberedValue);
            }
            composer.endReplaceableGroup();

            final MutableState mutableState = (MutableState) rememberedValue;
            composer.startReplaceableGroup(1157296644);

            //比较mutableState是否变化
            boolean changed = composer.changed(mutableState);

            //取出mutableStateUpdater
            Object mutableStateUpdater = composer.rememberedValue();
            //如果mutableState发生改变，或者此前没存过mutableStateUpdater，则新造一个
            if (changed || mutableStateUpdater == Composer.Companion.getEmpty()) {
                mutableStateUpdater = (Function0) new Function0<Unit>() {
                    @Override
                    public final void invoke() {
                        int HelloWorld$lambda$8;
                        //by委托，读mutableState
                        HelloWorld$lambda$8 = MainActivityKt.HelloWorld$lambda$8(mutableState);
                        //by委托，写mutableState
                        MainActivityKt.HelloWorld$lambda$9(mutableState, HelloWorld$lambda$8 + 1);
                    }
                };
                composer.updateRememberedValue(mutableStateUpdater);
            }
            composer.endReplaceableGroup();

            //Button
            //最后一个参数是ComposebleLambda，也就是Button的content参数，(@Composable RowScope.() -> Unit)
            //ComposableLambdaKt.composableLambda函数：把(@Composable RowScope.() -> Unit)这个@Composable存进SlotTable，并返回这个@Composable实例
            ButtonKt.Button(mutableStateUpdater, null, false, null, null, null, null, null, null, ComposableLambdaKt.composableLambda(composer, -1062752165, true, new Function3<RowScope, Composer, Integer, Unit>() {
                @Override 
                public Unit invoke(RowScope rowScope, Composer composer22, Integer num) {
                    invoke(rowScope, composer22, num.intValue());
                    return Unit.INSTANCE;
                }

                public final void invoke(RowScope Button, Composer composer22, int i2) {
                    int HelloWorld$lambda$8;
                    
                    if ((i2 & 81) == 16 && composer22.getSkipping()) {
                        composer22.skipToGroupEnd();
                        return;
                    }

                    StringBuilder sb = new StringBuilder("Hello: ");
                    HelloWorld$lambda$8 = MainActivityKt.HelloWorld$lambda$8(mutableState);//count
                    TextKt.m1663TextfLXpl1I(sb.append(HelloWorld$lambda$8).toString(), null, 0L, 0L, null, null, null, 0L, null, null, 0L, 0, false, 0, null, null, composer22, 0, 0, 65534);

                }
            }), composer, 805306368, 510);
            
        } else {
            composer.skipToGroupEnd();
        }

        ScopeUpdateScope endRestartGroup = composer.endRestartGroup();
        if (endRestartGroup == null) {
            return;
        }
        
        endRestartGroup.updateScope(new Function2<Composer, Integer, Unit>() { 
            public final void invoke(Composer composer22, int i2) {
                MainActivityKt.HelloWorld(composer22, i | 1);
            }
        });
    }

    public static final int HelloWorld$lambda$8(MutableState<Integer> mutableState) {
        return mutableState.getValue().intValue();
    }

    public static final void HelloWorld$lambda$9(MutableState<Integer> mutableState, int i) {
        mutableState.setValue(Integer.valueOf(i));
    }
