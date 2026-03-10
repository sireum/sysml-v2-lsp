package AADLTest {
    private import AADL::*;

    part def TempSensor :> Thread {
        out port currentTemp : DataPort;
    }

    part def TempController :> Process {
        part sensor : TempSensor;
    }
}
