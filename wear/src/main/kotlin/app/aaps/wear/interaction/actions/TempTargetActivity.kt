@file:Suppress("DEPRECATION")

package app.aaps.wear.interaction.actions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData.ActionTempTargetPreCheck
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.wear.R
import app.aaps.wear.interaction.utils.EditPlusMinusViewAdapter
import app.aaps.wear.interaction.utils.PlusMinusEditText
import app.aaps.wear.nondeprecated.GridPagerAdapterNonDeprecated
import java.text.DecimalFormat

class TempTargetActivity : ViewSelectorActivity() {

    var lowRange: PlusMinusEditText? = null
    var highRange: PlusMinusEditText? = null
    var time: PlusMinusEditText? = null
    var isMGDL = false
    var isSingleTarget = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAdapter(MyGridViewPagerAdapter())
        isMGDL = sp.getBoolean(R.string.key_units_mgdl, true)
        isSingleTarget = sp.getBoolean(R.string.key_single_target, true)
    }

    override fun onPause() {
        super.onPause()
        finish()
    }

    private inner class MyGridViewPagerAdapter : GridPagerAdapterNonDeprecated() {

        override fun getColumnCount(arg0: Int): Int {
            return if (isSingleTarget) 3 else 4
        }

        override fun getRowCount(): Int {
            return 1
        }

        override fun instantiateItem(container: ViewGroup, row: Int, col: Int): Any? = when {
            // Если col == 0 (первый столбец)
            col == 0 -> {
                // Создаем адаптер для интерфейса с элементами "плюс-минус"
                val view = LayoutInflater.from(applicationContext).inflate(R.layout.action_start, container, false)
                // Получаем корневой элемент интерфейса из адаптера
                val confirmButton = view.findViewById<ImageView>(R.id.startbutton)

                // Устанавливаем обработчик нажатия на кнопку
                confirmButton.setOnClickListener {
                    // Создаем объект действия с текущими настройками
                    val action = ActionTempTargetPreCheck(
                        ActionTempTargetPreCheck.TempTargetCommand.MANUAL, // Тип действия: ручной ввод
                        isMGDL, // Единицы измерения: mg/dL или mmol/L
                        SafeParse.stringToInt(time?.editText?.text.toString()), // Время действия в минутах
                        SafeParse.stringToDouble(lowRange?.editText?.text.toString()), // Нижний предел диапазона
                        if (isSingleTarget)
                            SafeParse.stringToDouble(lowRange?.editText?.text.toString()) // Если одиночная цель, берём нижний предел
                        else
                            SafeParse.stringToDouble(highRange?.editText?.text.toString()) // Если диапазон, берём верхний предел
                    )

                    // Отправляем действие через систему обмена сообщений
                    rxBus.send(EventWearToMobile(action))

                    // Показываем сообщение с подтверждением
                    showToast(this@TempTargetActivity, R.string.action_tempt_confirmation)

                    // Закрываем текущую активность и все связанные с ней
                    finishAffinity()
                }

                // Добавляем созданный интерфейс в контейнер
                container.addView(view)

                // Возвращаем созданный интерфейс
                view
            }

            // Код для второго экрана (col == 1)
            col == 1 -> {
                val viewAdapter = EditPlusMinusViewAdapter.getViewAdapter(sp, applicationContext, container, false)
                val view = viewAdapter.root
                //продолжаю ______________

                // Инициализируем значение времени (по умолчанию 60 минут), если в поле ничего не введено
                val initValue = SafeParse.stringToDouble(time?.editText?.text.toString(), 60.0)

                // Создаем объект для ввода времени с ограничениями от 0 до 24 часов (в минутах), шаг 5 минут
                time = PlusMinusEditText(viewAdapter, initValue, 0.0, 24 * 60.0, 5.0, DecimalFormat("0"), false, getString(R.string.pump_connection))

                // Добавляем созданный интерфейс в контейнер
                container.addView(view)

                // Устанавливаем фокус на созданный элемент
                view.requestFocus()

                // Возвращаем созданный интерфейс
                view
            }

            // Если col == 1 (второй столбец)
            col == 2 -> {
                // Создаем адаптер для интерфейса с элементами "плюс-минус"
                val viewAdapter = EditPlusMinusViewAdapter.getViewAdapter(sp, applicationContext, container, false)

                // Получаем корневой элемент интерфейса из адаптера
                val view = viewAdapter.root

                // Устанавливаем заголовок в зависимости от настройки isSingleTarget
                val title = if (isSingleTarget) getString(R.string.action_target) else getString(R.string.action_low)

                if (isMGDL) {
                    // Если используются единицы измерения mg/dL
                    val initValue = SafeParse.stringToDouble(lowRange?.editText?.text.toString(), 100.0)

                    // Создаем объект для ввода нижнего значения диапазона с ограничениями 72-180, шаг 1
                    lowRange = PlusMinusEditText(viewAdapter, initValue, 72.0, 180.0, 1.0, DecimalFormat("0"), false, title)
                } else {
                    // Если используются единицы измерения mmol/L
                    val initValue = SafeParse.stringToDouble(lowRange?.editText?.text.toString(), 5.5)

                    // Создаем объект для ввода нижнего значения диапазона с ограничениями 4.0-10.0, шаг 0.1
                    lowRange = PlusMinusEditText(viewAdapter, initValue, 4.0, 10.0, 0.1, DecimalFormat("#0.0"), false, title)
                }

                // Добавляем созданный интерфейс в контейнер
                container.addView(view)

                // Возвращаем созданный интерфейс
                view
            }

            // Если col == 2 и настройка isSingleTarget == false (третий столбец)
            col == 2 && !isSingleTarget -> {
                // Создаем адаптер для интерфейса с элементами "плюс-минус"
                val viewAdapter = EditPlusMinusViewAdapter.getViewAdapter(sp, applicationContext, container, false)

                // Получаем корневой элемент интерфейса из адаптера
                val view = viewAdapter.root

                if (isMGDL) {
                    // Если используются единицы измерения mg/dL
                    val initValue = SafeParse.stringToDouble(highRange?.editText?.text.toString(), 100.0)

                    // Создаем объект для ввода верхнего значения диапазона с ограничениями 72-180, шаг 1
                    highRange = PlusMinusEditText(viewAdapter, initValue, 72.0, 180.0, 1.0, DecimalFormat("0"), false, getString(R.string.action_high))
                } else {
                    // Если используются единицы измерения mmol/L
                    val initValue = SafeParse.stringToDouble(highRange?.editText?.text.toString(), 5.5)

                    // Создаем объект для ввода верхнего значения диапазона с ограничениями 4.0-10.0, шаг 0.1
                    highRange = PlusMinusEditText(viewAdapter, initValue, 4.0, 10.0, 0.1, DecimalFormat("#0.0"), false, getString(R.string.action_high))
                }

                // Добавляем созданный интерфейс в контейнер
                container.addView(view)

                // Возвращаем созданный интерфейс
                view
            }

            // Для всех других случаев (например, если col == 3)
                // Убираем кнопку с последнего экрана
            else -> {
                val view = LayoutInflater.from(applicationContext).inflate(R.layout.action_start, container, false)
                container.addView(view)
                view
            }
        }


        override fun destroyItem(container: ViewGroup, row: Int, col: Int, view: Any) {
            // Handle this to get the data before the view is destroyed?
            // Object should still be kept by this, just setup for re-init?
            container.removeView(view as View)
        }

        override fun isViewFromObject(view: View, `object`: Any): Boolean {
            return view === `object`
        }
    }
}
